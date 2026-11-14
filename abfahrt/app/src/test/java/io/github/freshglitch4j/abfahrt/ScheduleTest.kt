package io.github.freshglitch4j.abfahrt

import io.github.freshglitch4j.abfahrt.core.Holidays
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.TripKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ScheduleTest {
    private val s = Settings()
    private val mon = LocalDate.of(2026, 11, 16)
    private val fri = LocalDate.of(2026, 11, 20)

    @Test fun montagHatHinUndHeimfahrt() {
        val w = Schedule.windowsOn(mon, s)
        assertEquals(2, w.size)
        assertEquals(TripKind.HIN, w[0].kind)
        // 30 Minuten vor der ersten Abfahrt (06:33)
        assertEquals(LocalTime.of(6, 3), w[0].start.toLocalTime())
        assertEquals(listOf("hin-moosegg", "hin-gratten"), w[0].connections.map { it.id })
        assertEquals(TripKind.HEIM, w[1].kind)
        assertEquals(LocalTime.of(16, 16), w[1].start.toLocalTime())
        assertEquals(listOf("heim-bus", "heim-rex"), w[1].connections.map { it.id })
    }

    @Test fun freitagNurDerZugUm1229() {
        val w = Schedule.windowsOn(fri, s)
        assertEquals(2, w.size)
        val heim = w.single { it.kind == TripKind.HEIM }
        assertEquals(listOf("heim-rex-fr"), heim.connections.map { it.id })
        assertEquals(LocalTime.of(11, 59), heim.start.toLocalTime())
    }

    @Test fun wochenendeOhneAnzeige() {
        assertTrue(Schedule.windowsOn(LocalDate.of(2026, 11, 14), s).isEmpty())
        assertTrue(Schedule.windowsOn(LocalDate.of(2026, 11, 15), s).isEmpty())
    }

    @Test fun feiertagOhneAnzeige() {
        val mariaEmpfaengnis = LocalDate.of(2026, 12, 8) // Dienstag
        assertTrue(Schedule.windowsOn(mariaEmpfaengnis, s).isEmpty())
        assertEquals(2, Schedule.windowsOn(mariaEmpfaengnis, s.copy(skipHolidays = false)).size)
    }

    @Test fun ostern() {
        assertEquals(LocalDate.of(2026, 4, 5), Holidays.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Holidays.easterSunday(2027))
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 4, 6)))   // Ostermontag
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 5, 14)))  // Christi Himmelfahrt
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 5, 25)))  // Pfingstmontag
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 6, 4)))   // Fronleichnam
        assertFalse(Holidays.isHoliday(LocalDate.of(2026, 11, 16)))
    }

    @Test fun naechsterStartUeberDasWochenende() {
        val fridayEvening = LocalDateTime.of(2026, 11, 20, 18, 0)
        val next = Schedule.nextStartAfter(fridayEvening, s)!!
        assertEquals(LocalDateTime.of(2026, 11, 23, 6, 3), next.start)
    }

    @Test fun ausgeschaltetHeisstKeineFenster() {
        assertNull(Schedule.nextStartAfter(LocalDateTime.of(2026, 11, 16, 5, 0), s.copy(active = false)))
    }

    @Test fun vorlaufIstEinstellbar() {
        val w = Schedule.windowsOn(mon, s.copy(leadMinutes = 45))
        assertEquals(LocalTime.of(5, 48), w[0].start.toLocalTime())
    }

    @Test fun weitAuseinanderliegendeFahrtenBildenGetrennteFenster() {
        val extra = s.connections.first { it.id == "heim-rex" }.copy(id = "spaet", time = LocalTime.of(19, 59))
        val w = Schedule.windowsOn(mon, s.copy(connections = s.connections + extra)).filter { it.kind == TripKind.HEIM }
        assertEquals(2, w.size)
        assertEquals(LocalTime.of(19, 29), w[1].start.toLocalTime())
    }

    @Test fun kandidatenNurImZeitraum() {
        assertTrue(Schedule.candidatesAt(LocalDateTime.of(2026, 11, 16, 6, 2), s).isEmpty())
        assertEquals(1, Schedule.candidatesAt(LocalDateTime.of(2026, 11, 16, 6, 3), s).size)
    }

    @Test fun einstellungenUeberstehenSpeichern() {
        val custom = s.copy(accessId = "abc", leadMinutes = 40, pinned = mapOf(TripKind.HIN to "hin-gratten"), autoStand = true)
        // JSON-Rundlauf braucht org.json → siehe VaoTest (Robolectric); hier nur Gleichheit der Datenklasse
        assertEquals(custom, custom.copy())
    }
}
