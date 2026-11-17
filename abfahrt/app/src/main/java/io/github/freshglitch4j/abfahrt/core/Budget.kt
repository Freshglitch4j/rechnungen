package io.github.freshglitch4j.abfahrt.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Schätzt den Abfrageverbrauch der eingestellten Verbindungen, indem ein Tag
 * mit dem echten Abfragetakt durchgespielt wird (alle Fahrten pünktlich).
 */
object Budget {
    fun estimate(s: Settings, date: LocalDate): Int {
        if (s.accessId.isBlank() && s.active) return estimate(s.copy(accessId = "x"), date)
        var total = 0
        for (w in Schedule.windowsOn(date, s.copy(skipHolidays = false))) {
            var states = emptyMap<String, ConnState>()
            var now = w.start
            while (Engine.isActive(w, states, now)) {
                for (req in Engine.dueRequests(w, states, now, Int.MAX_VALUE)) {
                    total++
                    val board = Board(req.connections.map { c ->
                        val t = Engine.plannedAt(w, c)
                        Departure(t, t, c.line.ifEmpty { c.label }, c.line, "", c.towardsName, false, null, null, c.stopName)
                    })
                    states = Engine.apply(w, req, VaoResult.Ok(board), true, states, now)
                }
                val minute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                val due = Engine.nextDue(w, states, now, Int.MAX_VALUE)
                now = if (due != null && due.isAfter(now) && due.isBefore(minute)) due else minute
            }
        }
        return total
    }

    /** Voraussichtlicher Verbrauch je Wochentag (Montag bis Sonntag) einer gewöhnlichen Woche. */
    fun perWeekday(s: Settings, reference: LocalDate = LocalDate.of(2026, 11, 16)): Map<DayOfWeek, Int> {
        val monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DayOfWeek.entries.associateWith { estimate(s, monday.plusDays((it.value - 1).toLong())) }
    }
}
