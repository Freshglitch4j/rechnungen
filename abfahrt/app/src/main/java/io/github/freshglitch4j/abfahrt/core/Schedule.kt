package io.github.freshglitch4j.abfahrt.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

/** Gesetzliche Feiertage in Österreich (bundesweit). */
object Holidays {
    fun isHoliday(d: LocalDate): Boolean = d in ofYear(d.year)

    fun ofYear(year: Int): Set<LocalDate> {
        val easter = easterSunday(year)
        return setOf(
            LocalDate.of(year, Month.JANUARY, 1),
            LocalDate.of(year, Month.JANUARY, 6),
            easter.plusDays(1),   // Ostermontag
            LocalDate.of(year, Month.MAY, 1),
            easter.plusDays(39),  // Christi Himmelfahrt
            easter.plusDays(50),  // Pfingstmontag
            easter.plusDays(60),  // Fronleichnam
            LocalDate.of(year, Month.AUGUST, 15),
            LocalDate.of(year, Month.OCTOBER, 26),
            LocalDate.of(year, Month.NOVEMBER, 1),
            LocalDate.of(year, Month.DECEMBER, 8),
            LocalDate.of(year, Month.DECEMBER, 25),
            LocalDate.of(year, Month.DECEMBER, 26),
        )
    }

    /** Gaußsche Osterformel (gregorianisch). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}

/**
 * Ein Anzeigefenster: alle Fahrten einer Richtung an einem Tag, die zeitlich
 * zusammengehören. Die Anzeige beginnt [start] und endet dynamisch, sobald
 * alle Fahrten abgefahren sind – spätestens aber [hardEnd].
 */
data class Window(
    val kind: TripKind,
    val date: LocalDate,
    val connections: List<Connection>,
    val start: LocalDateTime,
    val firstPlanned: LocalDateTime,
    val lastPlanned: LocalDateTime,
) {
    val hardEnd: LocalDateTime get() = lastPlanned.plusMinutes(MAX_OVERRUN_MIN)

    /** Stabiler Schlüssel, z. B. für „heute ausblenden“. */
    val key: String get() = "$date/${kind.name}/${firstPlanned.toLocalTime()}"

    companion object {
        /** So lange wird eine verspätete Fahrt höchstens weiterverfolgt. */
        const val MAX_OVERRUN_MIN = 90L
    }
}

object Schedule {
    /** Fahrten einer Richtung, die höchstens so weit auseinanderliegen, bilden ein Fenster. */
    const val CLUSTER_GAP_MIN = 90L

    fun windowsOn(date: LocalDate, s: Settings): List<Window> {
        if (!s.active) return emptyList()
        if (s.skipHolidays && Holidays.isHoliday(date)) return emptyList()
        val dow: DayOfWeek = date.dayOfWeek
        val out = mutableListOf<Window>()
        for (kind in TripKind.entries) {
            val conns = s.connections
                .filter { it.enabled && it.kind == kind && dow in it.days }
                .sortedBy { it.time }
            var cluster = mutableListOf<Connection>()
            fun flush() {
                if (cluster.isEmpty()) return
                val first = date.atTime(cluster.first().time)
                val last = date.atTime(cluster.last().time)
                out += Window(kind, date, cluster.toList(), first.minusMinutes(s.leadMinutes.toLong()), first, last)
                cluster = mutableListOf()
            }
            for (c in conns) {
                if (cluster.isNotEmpty() &&
                    java.time.Duration.between(cluster.first().time, c.time).toMinutes() > CLUSTER_GAP_MIN
                ) flush()
                cluster += c
            }
            flush()
        }
        return out.sortedBy { it.start }
    }

    /** Fenster, die [now] noch nicht hinter sich haben (laufend oder künftig), nach Beginn sortiert. */
    fun upcoming(now: LocalDateTime, s: Settings, daysAhead: Int = 14): List<Window> {
        val out = mutableListOf<Window>()
        // Gestern mitnehmen: ein Fenster kann über Mitternacht reichen.
        for (i in -1..daysAhead) {
            out += windowsOn(now.toLocalDate().plusDays(i.toLong()), s).filter { it.hardEnd.isAfter(now) }
        }
        return out.sortedBy { it.start }
    }

    /** Fenster, deren Anzeigezeitraum [now] grundsätzlich einschließt (ohne Abfahrtsstatus). */
    fun candidatesAt(now: LocalDateTime, s: Settings): List<Window> =
        upcoming(now, s, daysAhead = 0).filter { !it.start.isAfter(now) }

    fun nextStartAfter(now: LocalDateTime, s: Settings): Window? =
        upcoming(now, s).firstOrNull { it.start.isAfter(now) }
}
