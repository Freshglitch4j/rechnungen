package io.github.freshglitch4j.abfahrt

import io.github.freshglitch4j.abfahrt.core.Board
import io.github.freshglitch4j.abfahrt.core.ConnState
import io.github.freshglitch4j.abfahrt.core.Departure
import io.github.freshglitch4j.abfahrt.core.Engine
import io.github.freshglitch4j.abfahrt.core.FailKind
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Match
import io.github.freshglitch4j.abfahrt.core.PassStop
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.Status
import io.github.freshglitch4j.abfahrt.core.TripKind
import io.github.freshglitch4j.abfahrt.core.VaoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class EngineTest {
    private val s = Settings(accessId = "key")
    private val mon = LocalDate.of(2026, 11, 16)
    private val heim = Schedule.windowsOn(mon, s).single { it.kind == TripKind.HEIM }
    private val hin = Schedule.windowsOn(mon, s).single { it.kind == TripKind.HIN }
    private val bus = heim.connections.single { it.id == "heim-bus" }
    private val rex = heim.connections.single { it.id == "heim-rex" }
    private fun at(h: Int, m: Int, sec: Int = 0) = LocalDateTime.of(2026, 11, 16, h, m, sec)

    private fun dep(h: Int, m: Int, delay: Long? = 0, name: String = "Bus 760", line: String = "760", dir: String = "Wörgl Hauptbahnhof",
                    cancelled: Boolean = false, stops: List<String> = emptyList()) = Departure(
        planned = at(h, m), realtime = delay?.let { at(h, m).plusMinutes(it) }, name = name, line = line, category = "",
        direction = dir, cancelled = cancelled, track = null, rtTrack = null, stopName = "", passStops = stops.map { PassStop(it, "") },
    )

    private fun found(d: Departure, at: LocalDateTime) = ConnState(lastAttempt = at, lastSuccess = at, match = Match.Found(d, false))

    // ------------------------------------------------------------ Zuordnung

    @Test fun exakteZuordnungUeberZeitUndLinie() {
        val board = Board(listOf(dep(16, 8, dir = "Breitenbach"), dep(16, 46, 5), dep(17, 46)))
        val m = Engine.match(bus, at(16, 46), board, true) as Match.Found
        assertEquals(at(16, 51), m.dep.realtime)
        assertFalse(m.timeDeviates)
    }

    @Test fun zweiZuegeZurSelbenMinuteWerdenUeberDieRichtungUnterschieden() {
        val board = Board(listOf(
            dep(16, 59, 0, "REX 5332", "REX 2", "Innsbruck Hbf", stops = listOf("Rattenberg-Kramsach", "Brixlegg", "Jenbach")),
            dep(16, 59, 3, "REX 5333", "REX 2", "Wörgl Hbf", stops = listOf("Wörgl Hauptbahnhof")),
        ))
        val m = Engine.match(rex, at(16, 59), board, serverFiltered = false) as Match.Found
        assertEquals("REX 5333", m.dep.name)
    }

    @Test fun richtungUeberGlobaleIdInDerHalteliste() {
        val toKufstein = Departure(at(12, 29), at(12, 29), "REX 5373", "REX 2", "", "Kufstein Bahnhof", false, null, null, "",
            listOf(PassStop("Woergl", "A=1@O=Wörgl Hbf@L=470117000@i=A×at:47:1170@ 470117000 "), PassStop("Kufstein", "at:47:1100")))
        val toInnsbruck = Departure(at(12, 27), at(12, 27), "REX 5350", "REX 2", "", "Innsbruck Hbf", false, null, null, "",
            listOf(PassStop("Jenbach", "at:47:11701 "), PassStop("Innsbruck", "at:47:1000")))
        assertEquals(true, Engine.goesTowards(toKufstein, rex))
        assertEquals(false, Engine.goesTowards(toInnsbruck, rex))
        assertNull(Engine.goesTowards(dep(16, 59), rex))
    }

    @Test fun geaenderteFahrplanzeitWirdErkannt() {
        val m = Engine.match(bus, at(16, 46), Board(listOf(dep(16, 44, 0))), true) as Match.Found
        assertTrue(m.timeDeviates)
    }

    @Test fun uhrzeitOhnePassendeFahrtZeigtAlternativen() {
        val gratten = hin.connections.single { it.id == "hin-gratten" }.copy(time = java.time.LocalTime.of(6, 37), line = "")
        val board = Board(listOf(
            dep(6, 28, name = "Bus 865", line = "865"), dep(6, 32, name = "Bus 4056", line = "4056"), dep(6, 42, name = "Bus 4055", line = "4055"),
        ))
        // 06:32 und 06:42 liegen gleich weit von 06:37 entfernt → nicht raten.
        val m = Engine.match(gratten, at(6, 37), board, true) as Match.NotFound
        assertEquals(listOf("06:32 Bus 4056", "06:42 Bus 4055"), m.alternatives.filter { !it.contains("865") })
    }

    @Test fun grattenbruecke0642MitLinie4055() {
        val gratten = hin.connections.single { it.id == "hin-gratten" }
        val board = Board(listOf(dep(6, 32, name = "Bus 4056", line = "4056"), dep(6, 42, 1, name = "Bus 4055", line = "4055")))
        val m = Engine.match(gratten, at(6, 42), board, true) as Match.Found
        assertEquals("Bus 4055", m.dep.name)
        assertFalse(m.timeDeviates)
    }

    @Test fun linienfilterVergleichtGanzeWoerter() {
        assertTrue(Engine.lineMatches("760", dep(1, 1)))
        assertFalse(Engine.lineMatches("76", dep(1, 1)))
        assertFalse(Engine.lineMatches("760", dep(1, 1, name = "Bus 7601", line = "7601")))
        assertTrue(Engine.lineMatches("rex", dep(1, 1, name = "REX 5333", line = "REX 2")))
        assertTrue(Engine.lineMatches("REX 2", dep(1, 1, name = "REX 5333", line = "REX 2")))
        assertTrue(Engine.lineMatches("", dep(1, 1)))
    }

    // ------------------------------------------------------------ Anzeige

    private fun rowFor(states: Map<String, ConnState>, now: LocalDateTime, settings: Settings = s, budget: Int = 50) =
        Engine.display(heim, states, settings, now, budget).rows.single { it.conn.id == "heim-bus" }

    @Test fun verspaetungMitEchtzeit() {
        val r = rowFor(mapOf(bus.id to found(dep(16, 46, 5), at(16, 38))), at(16, 39, 20))
        assertEquals(Status.DELAYED, r.status)
        assertEquals("16:46 → 16:51", Fmt.times(r))
        assertEquals("+5 Min", r.statusText)
        assertEquals("in 11 Min", Fmt.countdown(r, at(16, 39, 20)))
        assertEquals("Bus 760  16:46 → 16:51  +5 Min", Fmt.line(r))
    }

    @Test fun puenktlichNurMitEchtzeit() {
        val onTime = rowFor(mapOf(bus.id to found(dep(16, 46, 0), at(16, 38))), at(16, 39))
        assertEquals(Status.ON_TIME, onTime.status)
        assertEquals("16:46 → 16:46", Fmt.times(onTime))

        val noRt = rowFor(mapOf(bus.id to found(dep(16, 46, null), at(16, 38))), at(16, 39))
        assertEquals(Status.NO_REALTIME, noRt.status)
        assertEquals("keine Echtzeitdaten", noRt.statusText)
        assertEquals("16:46", Fmt.times(noRt))
        assertEquals("in 7 Min lt. Plan", Fmt.countdown(noRt, at(16, 39)))
        assertEquals("16:46?", Fmt.chip(noRt))
    }

    @Test fun frueherUndAusfall() {
        val early = rowFor(mapOf(bus.id to found(dep(16, 46, -1), at(16, 38))), at(16, 39))
        assertEquals(Status.EARLY, early.status)
        assertEquals("−1 Min", early.statusText)
        val cancelled = rowFor(mapOf(bus.id to found(dep(16, 46, null, cancelled = true), at(16, 38))), at(16, 39))
        assertEquals(Status.CANCELLED, cancelled.status)
        assertEquals("Ausfall", Fmt.chip(cancelled))
    }

    @Test fun veralteteDatenWerdenNieAlsAktuellGezeigt() {
        val st = found(dep(16, 46, 5), at(16, 38))
        // 2 Minuten alt bei 2-Minuten-Takt: noch aktuell
        assertEquals(Status.DELAYED, rowFor(mapOf(bus.id to st), at(16, 40)).status)
        // Abrufe fehlgeschlagen, Daten 6 Minuten alt: veraltet, keine erwartete Zeit mehr
        val failed = st.copy(lastAttempt = at(16, 43), error = FailKind.NETWORK, failures = 4)
        val r = rowFor(mapOf(bus.id to failed), at(16, 44))
        assertEquals(Status.STALE, r.status)
        assertNull(r.expected)
        assertEquals("16:46", Fmt.times(r))
        assertEquals("zuletzt +5 Min (16:38)", r.note)
    }

    @Test fun ohneSchluesselNurFahrplan() {
        val r = rowFor(emptyMap(), at(16, 30), s.copy(accessId = ""))
        assertEquals(Status.NO_KEY, r.status)
        assertNull(r.expected)
    }

    @Test fun offlineUndKontingent() {
        val offline = ConnState(lastAttempt = at(16, 30), error = FailKind.NETWORK, failures = 1)
        assertEquals(Status.OFFLINE, rowFor(mapOf(bus.id to offline), at(16, 31)).status)
        assertEquals(Status.QUOTA, rowFor(emptyMap(), at(16, 31), budget = 0).status)
        val auth = ConnState(lastAttempt = at(16, 30), error = FailKind.AUTH, errorMessage = "access denied")
        assertEquals(Status.AUTH, rowFor(mapOf(bus.id to auth), at(16, 31)).status)
        assertEquals(Status.WAITING, rowFor(emptyMap(), at(16, 31)).status)
    }

    @Test fun vornStehtDieNaechsteOderDieBevorzugteFahrt() {
        val states = mapOf(bus.id to found(dep(16, 46, 0), at(16, 30)), rex.id to found(dep(16, 59, 0, "REX 5333", "REX 2"), at(16, 30)))
        assertEquals("heim-bus", Engine.display(heim, states, s, at(16, 31), 50).primary!!.conn.id)
        val pinned = s.copy(pinned = mapOf(TripKind.HEIM to "heim-rex"))
        assertEquals("heim-rex", Engine.display(heim, states, pinned, at(16, 31), 50).primary!!.conn.id)
        // Bus abgefahren → Zug rückt nach vorn
        assertEquals("heim-rex", Engine.display(heim, states, s, at(16, 49), 50).primary!!.conn.id)
    }

    // ------------------------------------------------------------ Fenster und Takt

    @Test fun fensterEndetNachDerLetztenAbfahrt() {
        val states = mapOf(bus.id to found(dep(16, 46, 0), at(16, 45)), rex.id to found(dep(16, 59, 0, "REX 5333", "REX 2"), at(16, 58)))
        assertFalse(Engine.isActive(heim, states, at(16, 15, 59)))
        assertTrue(Engine.isActive(heim, states, at(16, 16)))
        assertTrue(Engine.isActive(heim, states, at(17, 0, 59)))
        assertFalse(Engine.isActive(heim, states, at(17, 1)))
    }

    @Test fun verspaetungVerlaengertDasFenster() {
        val states = mapOf(bus.id to found(dep(16, 46, 0), at(16, 45)), rex.id to found(dep(16, 59, 25, "REX 5333", "REX 2"), at(17, 20)))
        assertTrue(Engine.isActive(heim, states, at(17, 25)))
        assertFalse(Engine.isActive(heim, states, at(17, 26)))
    }

    @Test fun ohneEchtzeitBleibtDieFahrtLaengerStehen() {
        assertTrue(Engine.isActive(heim, emptyMap(), at(17, 4, 59)))
        assertFalse(Engine.isActive(heim, emptyMap(), at(17, 5)))
    }

    @Test fun taktWirdZurAbfahrtHinDichter() {
        assertEquals(600, Engine.intervalSec(30, 100))
        assertEquals(300, Engine.intervalSec(20, 100))
        assertEquals(180, Engine.intervalSec(10, 100))
        assertEquals(120, Engine.intervalSec(5, 100))
        assertEquals(60, Engine.intervalSec(2, 100))
        assertEquals(120, Engine.intervalSec(2, 20))   // knappes Kontingent streckt
        assertTrue(Engine.dueRequests(heim, emptyMap(), at(16, 20), 0).isEmpty())
    }

    @Test fun eineAbfrageJeHaltestelle() {
        val reqs = Engine.dueRequests(heim, emptyMap(), at(16, 16), 100)
        assertEquals(setOf("at:47:64459", "at:47:1172"), reqs.map { it.stopId }.toSet())
        val busReq = reqs.single { it.stopId == "at:47:64459" }
        assertEquals(at(16, 41), busReq.from)
        assertEquals("at:47:1170", busReq.towardsId)
        // nach einem Erfolg erst wieder nach Ablauf des Takts
        val states = Engine.apply(heim, busReq, VaoResult.Ok(Board(listOf(dep(16, 46, 0)))), true, emptyMap(), at(16, 16))
        assertTrue(Engine.dueRequests(heim, states, at(16, 20), 100).none { it.stopId == "at:47:64459" })
        assertTrue(Engine.dueRequests(heim, states, at(16, 26), 100).any { it.stopId == "at:47:64459" })
    }

    @Test fun fehlerWerdenSchnellWiederholtAberNichtBeiAbgelehntemSchluessel() {
        val req = Engine.dueRequests(heim, emptyMap(), at(16, 16), 100).single { it.stopId == "at:47:64459" }
        val net = Engine.apply(heim, req, VaoResult.Fail(FailKind.NETWORK, "offline"), true, emptyMap(), at(16, 16))
        assertTrue(Engine.dueRequests(heim, net, at(16, 16, 31), 100).any { it.stopId == "at:47:64459" })
        val auth = Engine.apply(heim, req, VaoResult.Fail(FailKind.AUTH, "denied"), true, emptyMap(), at(16, 16))
        assertTrue(Engine.dueRequests(heim, auth, at(16, 26), 100).none { it.stopId == "at:47:64459" })
    }
}
