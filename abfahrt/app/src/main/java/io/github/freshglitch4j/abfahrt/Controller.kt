package io.github.freshglitch4j.abfahrt

import android.content.Context
import io.github.freshglitch4j.abfahrt.core.Board
import io.github.freshglitch4j.abfahrt.core.BoardRequest
import io.github.freshglitch4j.abfahrt.core.ConnState
import io.github.freshglitch4j.abfahrt.core.Connection
import io.github.freshglitch4j.abfahrt.core.Departure
import io.github.freshglitch4j.abfahrt.core.Display
import io.github.freshglitch4j.abfahrt.core.Engine
import io.github.freshglitch4j.abfahrt.core.FailKind
import io.github.freshglitch4j.abfahrt.core.Http
import io.github.freshglitch4j.abfahrt.core.Match
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.TripKind
import io.github.freshglitch4j.abfahrt.core.UrlConnectionHttp
import io.github.freshglitch4j.abfahrt.core.VaoClient
import io.github.freshglitch4j.abfahrt.core.VaoResult
import io.github.freshglitch4j.abfahrt.core.Window
import io.github.freshglitch4j.abfahrt.core.ZONE
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArraySet

/** Laufzeitzustand der Anzeige (nur im Speicher; nach einem Neustart wird neu abgefragt). */
object Live {
    @Volatile var window: Window? = null
    @Volatile var states: Map<String, ConnState> = emptyMap()
    @Volatile var display: Display? = null
    @Volatile var demoStart: LocalDateTime? = null

    /** Für Tests austauschbar. */
    @Volatile var clock: () -> LocalDateTime = { LocalDateTime.now(ZONE) }
    @Volatile var http: Http = UrlConnectionHttp()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun publish(d: Display?) {
        display = d
        listeners.forEach { it() }
    }

    fun reset() {
        window = null; states = emptyMap(); display = null; demoStart = null
    }
}

/** Ergebnis eines Arbeitsschritts: was anzuzeigen ist und wann der nächste Schritt fällig wird. */
data class Step(val display: Display?, val nextWake: LocalDateTime?, val windowStarted: Boolean)

object Controller {
    const val ACTION_TICK = "io.github.freshglitch4j.abfahrt.TICK"
    const val ACTION_REFRESH = "io.github.freshglitch4j.abfahrt.REFRESH"
    const val ACTION_SWITCH = "io.github.freshglitch4j.abfahrt.SWITCH"
    const val ACTION_DISMISS = "io.github.freshglitch4j.abfahrt.DISMISS"
    const val ACTION_DEMO = "io.github.freshglitch4j.abfahrt.DEMO"
    const val ACTION_DEMO_STOP = "io.github.freshglitch4j.abfahrt.DEMO_STOP"

    const val DEMO_MINUTES = 5L

    /** Ist jetzt – ohne Netzabfrage beurteilt – etwas anzuzeigen? */
    fun somethingActive(context: Context, now: LocalDateTime = Live.clock()): Boolean {
        if (demoActive(now)) return true
        val store = Store(context)
        return pickWindow(store, store.settings, now) != null
    }

    private fun demoActive(now: LocalDateTime): Boolean {
        val start = Live.demoStart ?: return false
        return now.isBefore(start.plusMinutes(DEMO_MINUTES))
    }

    private fun pickWindow(store: Store, s: Settings, now: LocalDateTime): Window? {
        val dismissed = store.dismissedKey
        return Schedule.candidatesAt(now, s).firstOrNull { w ->
            w.key != dismissed && Engine.isActive(w, statesFor(w), now)
        }
    }

    /** Bereits bekannte Zustände gelten nur für dasselbe Fenster und unveränderte Fahrten. */
    private fun statesFor(w: Window): Map<String, ConnState> {
        val cur = Live.window ?: return emptyMap()
        if (cur.key != w.key) return emptyMap()
        val same = cur.connections.associateBy { it.id }
        return Live.states.filterKeys { id -> w.connections.any { it.id == id && it == same[id] } }
    }

    @Synchronized
    fun step(context: Context, action: String): Step {
        val store = Store(context)
        val now = Live.clock()
        var s = store.settings

        when (action) {
            ACTION_DEMO -> { Live.demoStart = now; store.log("Demo gestartet") }
            ACTION_DEMO_STOP -> Live.demoStart = null
        }
        if (demoActive(now)) return demoStep(now, s)
        Live.demoStart = null

        val before = Live.window?.key
        var w = pickWindow(store, s, now)
        if (w == null) {
            if (before != null) store.log("Fenster beendet: $before")
            Live.window = null; Live.states = emptyMap()
            Live.publish(null)
            return Step(null, null, false)
        }
        var states = statesFor(w)
        val started = before != w.key
        if (started) store.log("Fenster gestartet: ${w.key} (${w.connections.joinToString { it.label + " " + it.time }})")
        Live.window = w
        Live.states = states

        when (action) {
            ACTION_DISMISS -> {
                store.dismissedKey = w.key
                store.countDismiss(now.toLocalDate())
                store.log("Ausgeblendet: ${w.key}")
                Live.window = null; Live.states = emptyMap()
                Live.publish(null)
                return Step(null, null, false)
            }
            ACTION_SWITCH -> {
                s = switchPinned(s, w, states, now)
                store.settings = s
            }
        }

        val today = now.toLocalDate()
        if (s.accessId.isNotBlank()) {
            val reqs = Engine.dueRequests(w, states, now, store.budgetLeft(today), force = action == ACTION_REFRESH)
            if (reqs.isNotEmpty()) {
                val client = VaoClient(Live.http, s.accessId, store, onCountedRequest = { store.countRequest(today) })
                for (req in reqs) {
                    if (store.budgetLeft(today) <= 0) break
                    val (result, filtered) = fetch(client, store, req)
                    logResult(store, req, result)
                    if (result is VaoResult.Fail && result.kind == FailKind.QUOTA) store.markExhausted(s.dailyLimit, today)
                    states = Engine.apply(w, req, result, filtered, states, Live.clock())
                }
                Live.states = states
            }
        }

        val after = Live.clock()
        // Mit den neuen Daten kann das Fenster bereits vorbei sein (z. B. Fahrt fiel aus).
        if (!Engine.isActive(w, states, after)) {
            store.log("Fenster beendet: ${w.key}")
            Live.window = null; Live.states = emptyMap()
            Live.publish(null)
            return Step(null, null, false)
        }
        val display = Engine.display(w, states, s, after, store.budgetLeft(today))
        Live.publish(display)
        return Step(display, nextWake(w, states, s, after, store.budgetLeft(today)), started)
    }

    /** Nächster Weckzeitpunkt: zur vollen Minute (Countdown) oder früher, wenn eine Abfrage fällig wird. */
    private fun nextWake(w: Window, states: Map<String, ConnState>, s: Settings, now: LocalDateTime, budgetLeft: Int): LocalDateTime {
        val minute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).plusSeconds(1)
        val due = if (s.accessId.isBlank()) null else Engine.nextDue(w, states, now, budgetLeft)
        val t = if (due != null && due.isBefore(minute)) due else minute
        val earliest = now.plusSeconds(5)
        return if (t.isBefore(earliest)) earliest else t
    }

    /**
     * Fragt die Tafel mit Richtungsfilter ab. Liefert der Server damit nichts
     * oder lehnt er die Richtungs-ID ab, wird einmal ohne Filter gefragt und
     * die Richtung anhand der Halteliste selbst geprüft.
     */
    private fun fetch(client: VaoClient, store: Store, req: BoardRequest): Pair<VaoResult<Board>, Boolean> {
        val works = store.directionFilterWorks
        if (req.towardsId.isEmpty() || works == false) {
            return client.departureBoard(req.stopId, req.from, req.durationMin, null) to false
        }
        val r = client.departureBoard(req.stopId, req.from, req.durationMin, req.towardsId)
        when {
            r is VaoResult.Ok && r.value.departures.isNotEmpty() -> {
                if (works == null) store.directionFilterWorks = true
                return r to true
            }
            r is VaoResult.Ok && works == true -> return r to true
            r is VaoResult.Fail && r.kind != FailKind.REQUEST -> return r to true
        }
        val r2 = client.departureBoard(req.stopId, req.from, req.durationMin, null)
        if (r2 is VaoResult.Ok) {
            val reachable = r2.value.departures.any { d -> req.connections.any { c -> Engine.goesTowards(d, c) == true } }
            if (reachable) {
                store.directionFilterWorks = false
                store.log("Richtungsfilter des Servers greift nicht – Richtung wird in der App geprüft")
            } else if (r is VaoResult.Ok) {
                store.directionFilterWorks = true
                return r to true
            }
        }
        return r2 to false
    }

    private fun logResult(store: Store, req: BoardRequest, result: VaoResult<Board>) {
        when (result) {
            is VaoResult.Ok -> store.log("Abfrage ${req.stopId}: ${result.value.departures.size} Abfahrten")
            is VaoResult.Fail -> store.log("Abfrage ${req.stopId} fehlgeschlagen: ${result.kind} ${result.code} ${result.message}".trim())
        }
    }

    /** Wechselt die bevorzugte Fahrt des Fensters zur nächsten noch offenen. */
    private fun switchPinned(s: Settings, w: Window, states: Map<String, ConnState>, now: LocalDateTime): Settings {
        val current = Engine.display(w, states, s, now, 1).primary?.conn?.id
        val open = w.connections.filter { !Engine.isGone(w, it, states[it.id], now) }
        if (open.size < 2) return s
        val idx = open.indexOfFirst { it.id == current }
        val next = open[(idx + 1) % open.size]
        return s.copy(pinned = s.pinned + (w.kind to next.id))
    }

    // ---------------------------------------------------------------- Demo

    /** Beispielanzeige mit erfundenen Werten, um Sperrbildschirm und Widget zu prüfen. */
    private fun demoStep(now: LocalDateTime, s: Settings): Step {
        val start = Live.demoStart!!
        val base = start.truncatedTo(ChronoUnit.MINUTES)
        val days = DayOfWeek.entries.toSet()
        val bus = Connection("demo-bus", TripKind.HEIM, "Bus 760", "Kundl Gemeindesaal", "demo", base.plusMinutes(9).toLocalTime(), days)
        val rex = Connection("demo-rex", TripKind.HEIM, "REX 2", "Kundl Bahnhof", "demo", base.plusMinutes(22).toLocalTime(), days)
        val w = Window(TripKind.HEIM, base.toLocalDate(), listOf(bus, rex), start, base.plusMinutes(9), base.plusMinutes(22))
        fun dep(c: Connection, delay: Long) = Departure(
            planned = Engine.plannedAt(w, c), realtime = Engine.plannedAt(w, c).plusMinutes(delay),
            name = c.label, line = "", category = "", direction = "Wörgl Hbf", cancelled = false,
            track = null, rtTrack = null, stopName = c.stopName,
        )
        // Stand wie im Betrieb: alle zwei Minuten erneuert
        val asOf = base.plusMinutes(Duration.between(base, now).toMinutes() / 2 * 2)
        val states = mapOf(
            bus.id to ConnState(asOf, asOf, Match.Found(dep(bus, 5), false)),
            rex.id to ConnState(asOf, asOf, Match.Found(dep(rex, 0), false)),
        )
        val display = Engine.display(w, states, s.copy(pinned = emptyMap(), accessId = "demo"), now, 100, demo = true)
        Live.publish(display)
        val minute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).plusSeconds(1)
        val end = start.plusMinutes(DEMO_MINUTES)
        return Step(display, if (minute.isAfter(end)) end.plusSeconds(1) else minute, false)
    }
}
