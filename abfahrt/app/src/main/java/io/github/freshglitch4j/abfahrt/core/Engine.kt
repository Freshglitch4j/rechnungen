package io.github.freshglitch4j.abfahrt.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Ergebnis der Zuordnung einer überwachten Fahrt zur Abfahrtstafel. */
sealed class Match {
    /** [timeDeviates]: Fahrplanzeit weicht von der eingestellten Uhrzeit ab. */
    data class Found(val dep: Departure, val timeDeviates: Boolean) : Match()
    data class NotFound(val alternatives: List<String>) : Match()
}

/** Abfragestand einer Fahrt innerhalb eines Fensters. */
data class ConnState(
    val lastAttempt: LocalDateTime? = null,
    val lastSuccess: LocalDateTime? = null,
    val match: Match? = null,
    val error: FailKind? = null,
    val errorMessage: String = "",
    val failures: Int = 0,
)

enum class Status {
    ON_TIME, DELAYED, EARLY, CANCELLED,
    /** Fahrt gefunden, aber der Betreiber liefert (noch) keine Prognose. */ NO_REALTIME,
    /** Es gab Echtzeitdaten, sie konnten aber nicht mehr aktualisiert werden. */ STALE,
    NOT_FOUND, NO_KEY, AUTH, QUOTA, OFFLINE, WAITING,
}

data class Row(
    val conn: Connection,
    val planned: LocalDateTime,
    /** Nur gesetzt, wenn aktuelle Echtzeitdaten vorliegen. */
    val expected: LocalDateTime?,
    val delayMin: Int?,
    val status: Status,
    val statusText: String,
    val note: String,
    val track: String?,
    /** Zeitpunkt, zu dem die Daten dieser Zeile abgerufen wurden. */
    val asOf: LocalDateTime?,
    val gone: Boolean,
    val primary: Boolean,
) {
    val hasRealtime: Boolean get() = expected != null
    val effective: LocalDateTime get() = expected ?: planned
    val lineLabel: String get() = conn.label.ifEmpty { "Fahrt" }
}

data class Display(
    val window: Window,
    val rows: List<Row>,
    val now: LocalDateTime,
    val demo: Boolean = false,
) {
    val primary: Row? get() = rows.firstOrNull { it.primary } ?: rows.firstOrNull()
    val others: List<Row> get() = rows.filter { it !== primary }
    /** Jüngster Datenstand über alle Zeilen. */
    val asOf: LocalDateTime? get() = rows.mapNotNull { it.asOf }.maxOrNull()
}

/** Eine fällige Abfrage: eine Abfahrtstafel deckt alle Fahrten derselben Haltestelle und Richtung ab. */
data class BoardRequest(
    val stopId: String,
    val towardsId: String,
    val from: LocalDateTime,
    val durationMin: Int,
    val connections: List<Connection>,
)

object Engine {
    /** Abfahrten werden ab so vielen Minuten vor der Planzeit abgefragt (frühere Abfahrt möglich). */
    const val BOARD_LEAD_MIN = 5L
    /** Eine Fahrt mit Echtzeit gilt so lange nach der erwarteten Abfahrt noch als sichtbar. */
    const val LINGER_RT_SEC = 120L
    /** Ohne Echtzeit ist die tatsächliche Abfahrt unbekannt – länger stehen lassen. */
    const val LINGER_PLAN_SEC = 360L
    /** Höchstabweichung, bis zu der eine Fahrt mit anderer Planzeit noch zugeordnet wird. */
    const val MATCH_TOLERANCE_MIN = 6L

    // ---------------------------------------------------------------- Zuordnung

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }

    fun lineMatches(filter: String, dep: Departure): Boolean {
        val want = tokens(filter)
        if (want.isEmpty()) return true
        val have = (tokens(dep.name) + tokens(dep.line) + tokens(dep.category)).toSet()
        return want.all { it in have }
    }

    private fun normStop(s: String): String = s.lowercase()
        .replace("hauptbahnhof", "hbf").replace("bahnhof", "bf")
        .replace(Regex("[^\\p{L}\\p{Nd}]+"), "")

    /** Prüft anhand der mitgelieferten Halteliste, ob die Fahrt später am Zielhalt hält. null = unbekannt. */
    fun goesTowards(dep: Departure, c: Connection): Boolean? {
        if (c.towardsId.isEmpty() && c.towardsName.isEmpty()) return true
        if (dep.passStops.isEmpty()) return null
        val wantName = normStop(c.towardsName)
        return dep.passStops.any { s ->
            (c.towardsId.isNotEmpty() && Regex("(^|[^\\p{Nd}:])" + Regex.escape(c.towardsId) + "($|[^\\p{Nd}])").containsMatchIn(s.ids)) ||
                (wantName.isNotEmpty() && normStop(s.name).let { it.startsWith(wantName) || wantName.startsWith(it) && it.length >= 5 })
        }
    }

    private fun describe(d: Departure): String {
        val what = d.name.ifEmpty { d.line }.ifEmpty { d.category }
        return "${Fmt.time(d.planned)} $what".trim()
    }

    /**
     * Sucht die überwachte Fahrt in der Tafel. [serverFiltered]: der Server hat
     * bereits nach Richtung gefiltert – dann entfällt die eigene Richtungsprüfung.
     */
    fun match(c: Connection, plannedAt: LocalDateTime, board: Board, serverFiltered: Boolean): Match {
        var cands = board.departures.filter { lineMatches(c.line, it) }
        if (!serverFiltered) cands = cands.filter { goesTowards(it, c) != false }
        val exact = cands.filter { it.planned == plannedAt }
        if (exact.size == 1) return Match.Found(exact[0], false)
        if (exact.size > 1) {
            // Mehrere Fahrten zur selben Minute: die mit nachweislich passender Richtung.
            val sure = exact.filter { goesTowards(it, c) == true }
            return Match.Found((if (sure.size == 1) sure else exact)[0], false)
        }
        val near = cands
            .map { it to Math.abs(Duration.between(plannedAt, it.planned).toMinutes()) }
            .filter { it.second <= MATCH_TOLERANCE_MIN }
            .sortedBy { it.second }
        if (near.size == 1 || (near.size > 1 && near[0].second < near[1].second)) {
            return Match.Found(near[0].first, true)
        }
        val alts = board.departures
            .filter { serverFiltered || goesTowards(it, c) != false }
            .sortedBy { Math.abs(Duration.between(plannedAt, it.planned).toMinutes()) }
            .take(3).sortedBy { it.planned }.map(::describe)
        return Match.NotFound(alts)
    }

    // ---------------------------------------------------------------- Abfragetakt

    /**
     * Abstand zwischen zwei Abfragen einer Fahrt, abhängig von den Minuten bis
     * zur erwarteten Abfahrt. Dicht ist der Takt dort, wo die Entscheidung
     * „jetzt losgehen“ fällt; das Tageskontingent (VAO Start: 100) reicht so
     * für alle Fenster eines Tages.
     */
    fun intervalSec(minutesToDeparture: Long, budgetLeft: Int): Long {
        val base = when {
            minutesToDeparture > 25 -> 600L
            minutesToDeparture > 16 -> 300L
            minutesToDeparture > 8 -> 180L
            minutesToDeparture > 3 -> 120L
            else -> 60L
        }
        return when {
            budgetLeft <= 0 -> Long.MAX_VALUE / 4
            budgetLeft < 10 -> base * 3
            budgetLeft < 25 -> base * 2
            else -> base
        }
    }

    private fun retrySec(st: ConnState): Long = when (st.error) {
        FailKind.NETWORK, FailKind.SERVER, FailKind.PARSE -> if (st.failures <= 2) 30L else 60L
        FailKind.AUTH, FailKind.REQUEST -> 900L
        FailKind.QUOTA -> 3600L
        else -> 0L
    }

    fun plannedAt(w: Window, c: Connection): LocalDateTime = w.date.atTime(c.time)

    /** Beste bekannte Abfahrtszeit – auch aus älteren Daten, nur für Takt und Fensterende. */
    private fun bestKnown(w: Window, c: Connection, st: ConnState?): LocalDateTime {
        val m = st?.match
        return if (m is Match.Found) (m.dep.realtime ?: m.dep.planned) else plannedAt(w, c)
    }

    private fun isCancelled(st: ConnState?): Boolean = (st?.match as? Match.Found)?.dep?.cancelled == true

    private fun knowsRealtime(st: ConnState?): Boolean = (st?.match as? Match.Found)?.dep?.realtime != null

    fun isGone(w: Window, c: Connection, st: ConnState?, now: LocalDateTime): Boolean {
        val linger = if (knowsRealtime(st) || isCancelled(st)) LINGER_RT_SEC else LINGER_PLAN_SEC
        return !now.isBefore(bestKnown(w, c, st).plusSeconds(linger))
    }

    /** Das Fenster wird angezeigt, solange mindestens eine Fahrt noch nicht abgefahren ist. */
    fun isActive(w: Window, states: Map<String, ConnState>, now: LocalDateTime): Boolean {
        if (now.isBefore(w.start) || !now.isBefore(w.hardEnd)) return false
        return w.connections.any { !isGone(w, it, states[it.id], now) }
    }

    /** Abstand bis zur nächsten Abfrage dieser Fahrt nach einem erfolgreichen Abruf. */
    private fun cadenceSec(w: Window, c: Connection, st: ConnState, now: LocalDateTime, budgetLeft: Int): Long {
        val minutes = Duration.between(now, bestKnown(w, c, st)).toMinutes()
        val normal = intervalSec(minutes, budgetLeft)
        return when {
            // Fahrt steht nicht im Fahrplan: das ändert sich nicht im Minutentakt.
            st.match is Match.NotFound -> maxOf(normal, 300L)
            // Planzeit vorbei und der Betreiber liefert keine Prognose: seltener nachsehen.
            minutes < 0 && !knowsRealtime(st) -> maxOf(normal, 120L)
            else -> normal
        }
    }

    private fun isDue(w: Window, c: Connection, st: ConnState?, now: LocalDateTime, budgetLeft: Int): Boolean {
        if (isGone(w, c, st, now)) return false
        val last = st?.lastAttempt ?: return true
        val sinceLast = Duration.between(last, now).seconds
        if (st.error != null) return sinceLast >= retrySec(st)
        return sinceLast >= cadenceSec(w, c, st, now, budgetLeft)
    }

    fun dueRequests(w: Window, states: Map<String, ConnState>, now: LocalDateTime, budgetLeft: Int, force: Boolean = false): List<BoardRequest> {
        if (budgetLeft <= 0) return emptyList()
        return w.connections.groupBy { it.stopId to it.towardsId }.mapNotNull { (key, conns) ->
            val live = conns.filter { !isGone(w, it, states[it.id], now) }
            if (live.isEmpty()) return@mapNotNull null
            if (!force && live.none { isDue(w, it, states[it.id], now, budgetLeft) }) return@mapNotNull null
            val first = conns.minOf { plannedAt(w, it) }
            val last = conns.maxOf { plannedAt(w, it) }
            BoardRequest(
                stopId = key.first,
                towardsId = key.second,
                from = first.minusMinutes(BOARD_LEAD_MIN),
                durationMin = (Duration.between(first, last).toMinutes() + BOARD_LEAD_MIN + Window.MAX_OVERRUN_MIN).toInt(),
                connections = conns,
            )
        }
    }

    /** Verarbeitet das Ergebnis einer Abfrage und liefert die neuen Zustände. */
    fun apply(
        w: Window,
        req: BoardRequest,
        result: VaoResult<Board>,
        serverFiltered: Boolean,
        states: Map<String, ConnState>,
        now: LocalDateTime,
    ): Map<String, ConnState> {
        val out = states.toMutableMap()
        for (c in req.connections) {
            val old = states[c.id] ?: ConnState()
            out[c.id] = when (result) {
                is VaoResult.Ok -> ConnState(
                    lastAttempt = now, lastSuccess = now,
                    match = match(c, plannedAt(w, c), result.value, serverFiltered),
                )
                is VaoResult.Fail -> old.copy(
                    lastAttempt = now, error = result.kind, errorMessage = result.message, failures = old.failures + 1,
                )
            }
        }
        return out
    }

    /** Wann frühestens wieder eine Abfrage fällig wird (für den nächsten Weckzeitpunkt). */
    fun nextDue(w: Window, states: Map<String, ConnState>, now: LocalDateTime, budgetLeft: Int): LocalDateTime? {
        if (budgetLeft <= 0) return null
        return w.connections.filter { !isGone(w, it, states[it.id], now) }.mapNotNull { c ->
            val st = states[c.id]
            val last = st?.lastAttempt ?: return@mapNotNull now
            if (st.error != null) return@mapNotNull last.plusSeconds(retrySec(st))
            last.plusSeconds(cadenceSec(w, c, st, now, budgetLeft))
        }.minOrNull()
    }

    // ---------------------------------------------------------------- Anzeige

    /** Ab diesem Alter gelten Daten als veraltet: ein ausgefallener Abruf plus Reserve. */
    fun staleAfterSec(minutesToDeparture: Long): Long = intervalSec(minutesToDeparture, Int.MAX_VALUE) + 150

    fun display(w: Window, states: Map<String, ConnState>, s: Settings, now: LocalDateTime, budgetLeft: Int, demo: Boolean = false): Display {
        val rows = w.connections.map { c -> row(w, c, states[c.id], s, now, budgetLeft) }
        val open = rows.filter { !it.gone }
        val pinnedId = s.pinned[w.kind]
        val primary = open.firstOrNull { it.conn.id == pinnedId }
            ?: open.minByOrNull { it.effective }
            ?: rows.lastOrNull()
        return Display(w, rows.map { if (it === primary) it.copy(primary = true) else it }, now, demo)
    }

    private fun row(w: Window, c: Connection, st: ConnState?, s: Settings, now: LocalDateTime, budgetLeft: Int): Row {
        val gone = isGone(w, c, st, now)
        val plannedCfg = plannedAt(w, c)
        val m = st?.match
        val found = m as? Match.Found
        val planned = found?.dep?.planned ?: plannedCfg
        val minutes = Duration.between(now, bestKnown(w, c, st)).toMinutes()
        val age = st?.lastSuccess?.let { Duration.between(it, now).seconds }
        val fresh = age != null && age <= staleAfterSec(minutes)

        fun base(status: Status, text: String, note: String = "", expected: LocalDateTime? = null, delay: Int? = null) = Row(
            conn = c, planned = planned, expected = expected, delayMin = delay, status = status, statusText = text,
            note = note, track = found?.dep?.let { it.rtTrack ?: it.track }, asOf = st?.lastSuccess, gone = gone, primary = false,
        )

        val deviates = if (found?.timeDeviates == true) "Fahrplan: ${Fmt.time(planned)} statt ${Fmt.time(plannedCfg)}" else ""

        // Frische Daten zuerst – sie sind die einzige Grundlage für „pünktlich“ oder eine Verspätung.
        if (fresh && found != null) {
            val d = found.dep
            if (d.cancelled) return base(Status.CANCELLED, "fällt aus", deviates)
            val rt = d.realtime ?: return base(Status.NO_REALTIME, "keine Echtzeitdaten", deviates)
            val delay = d.delayMinutes ?: 0
            return when {
                delay > 0 -> base(Status.DELAYED, "+$delay Min", deviates, rt, delay)
                delay < 0 -> base(Status.EARLY, "−${-delay} Min", deviates.ifEmpty { "fährt früher" }, rt, delay)
                else -> base(Status.ON_TIME, "pünktlich", deviates, rt, 0)
            }
        }
        if (fresh && m is Match.NotFound) {
            val note = if (m.alternatives.isEmpty()) "keine Fahrt um diese Zeit" else "dort: " + m.alternatives.joinToString(", ")
            return base(Status.NOT_FOUND, "nicht im Fahrplan", note)
        }
        // Keine frischen Daten: Ursache benennen, niemals alte Werte als aktuell zeigen.
        if (s.accessId.isBlank()) return base(Status.NO_KEY, "nur Fahrplan", "kein VAO-Schlüssel hinterlegt")
        val lastAt = st?.lastSuccess
        val last = found?.dep?.takeIf { lastAt != null }?.let { d ->
            val was = when {
                d.cancelled -> "fällt aus"
                d.realtime == null -> null
                (d.delayMinutes ?: 0) > 0 -> "+${d.delayMinutes} Min"
                (d.delayMinutes ?: 0) < 0 -> "−${-(d.delayMinutes ?: 0)} Min"
                else -> "pünktlich"
            }
            was?.let { "zuletzt $it (${Fmt.time(lastAt!!)})" }
        }.orEmpty()
        return when {
            st?.error == FailKind.AUTH -> base(Status.AUTH, "Schlüssel abgelehnt", st.errorMessage.take(80))
            st?.error == FailKind.QUOTA || budgetLeft <= 0 -> base(Status.QUOTA, "Tageslimit erreicht", last)
            st?.error == FailKind.REQUEST -> base(Status.OFFLINE, "Abfrage abgelehnt", st.errorMessage.take(80))
            st?.lastSuccess != null -> base(Status.STALE, "Echtzeit veraltet", last)
            st?.error != null -> base(Status.OFFLINE, "keine Verbindung", "")
            else -> base(Status.WAITING, "wird abgefragt …")
        }
    }
}

/** Texte für alle Anzeigen (Benachrichtigung, Widget, Standanzeige). */
object Fmt {
    fun time(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)
    fun time(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** „16:46 → 16:51“ mit Echtzeit, sonst nur die Planzeit. */
    fun times(r: Row): String =
        if (r.expected != null) "${time(r.planned)} → ${time(r.expected)}" else time(r.planned)

    /** Minuten bis zur (erwarteten) Abfahrt, abgerundet – so viel Zeit bleibt mindestens. */
    fun minutesLeft(r: Row, now: LocalDateTime): Long =
        Math.floorDiv(Duration.between(now, r.effective).seconds, 60L)

    fun countdown(r: Row, now: LocalDateTime): String {
        if (r.status == Status.CANCELLED || r.status == Status.NOT_FOUND) return ""
        val secs = Duration.between(now, r.effective).seconds
        val min = Math.floorDiv(secs, 60L)
        val plan = if (r.hasRealtime) "" else " lt. Plan"
        return when {
            secs < -59 -> if (r.hasRealtime) "abgefahren" else "Planzeit vorbei"
            min <= 0 -> "jetzt$plan"
            min >= 60 -> "in ${min / 60} Std ${min % 60} Min$plan"
            else -> "in $min Min$plan"
        }
    }

    /** Eine Zeile, z. B. „Bus 760  16:46 → 16:51  +5 Min“. */
    fun line(r: Row): String = "${r.lineLabel}  ${times(r)}  ${r.statusText}"

    fun where(r: Row): String = r.conn.stopName + (r.track?.let { " · Steig $it" } ?: "")

    fun asOf(d: Display): String = d.asOf?.let { "Stand ${time(it)}" } ?: "noch keine Daten"

    /** Kurztext für die Statusleisten-Kapsel (höchstens 7 Zeichen). */
    fun chip(r: Row): String = when {
        r.status == Status.CANCELLED -> "Ausfall"
        r.expected != null -> time(r.expected)
        else -> time(r.planned) + "?"
    }
}
