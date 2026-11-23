package io.github.freshglitch4j.abfahrt

import io.github.freshglitch4j.abfahrt.core.FailKind
import io.github.freshglitch4j.abfahrt.core.MemoryVersionCache
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.TripKind
import io.github.freshglitch4j.abfahrt.core.VaoClient
import io.github.freshglitch4j.abfahrt.core.VaoResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class VaoTest {
    private val from = LocalDateTime.of(2026, 11, 16, 16, 41)

    @Test fun abfahrtstafelMitUndOhneEchtzeit() {
        // Aufbau wie im Handbuch-Beispiel (dort XML; JSON nach den Konvertierungsregeln)
        val body = """
        {"Departure":[
          {"JourneyDetailRef":{"ref":"1|74130|0|81|16112026"},"JourneyStatus":"P",
           "ProductAtStop":{"name":"Bus 760","internalName":"760","displayNumber":"760","num":"31313","line":"760","catOutL":"Regionalbus","operator":"Busreisen Tirol"},
           "name":"Bus 760","type":"ST","stop":"Kundl Gemeindesaal","stopid":"A=1@O=Kundl Gemeindesaal@L=476445900@","stopExtId":"476445900",
           "prognosisType":"PROGNOSED","time":"16:46:00","date":"2026-11-16","rtTime":"16:51:00","rtDate":"2026-11-16",
           "reachable":true,"direction":"Wörgl Hauptbahnhof","altId":["at:47:64459:0:1"]},
          {"ProductAtStop":{"name":"Bus 760","displayNumber":"760"},"name":"Bus 760","stop":"Kundl Gemeindesaal",
           "time":"17:46:00","date":"2026-11-16","direction":"Wörgl Hauptbahnhof","track":"A"},
          {"Product":[{"name":"REX 5333","line":"REX 2","catOutS":"REX"}],"name":"REX 5333","stop":"Kundl Bahnhof",
           "time":"23:59:00","date":"2026-11-16","rtTime":"00:02:00","rtDate":"2026-11-17","cancelled":"true","rtTrack":"2","track":"1"}
        ],"serverVersion":"2.52.1","requestId":"abfahrt"}
        """
        val board = (VaoClient.parseBoard(body) as VaoResult.Ok).value
        assertEquals(3, board.departures.size)
        val a = board.departures[0]
        assertEquals(LocalDateTime.of(2026, 11, 16, 16, 46), a.planned)
        assertEquals(LocalDateTime.of(2026, 11, 16, 16, 51), a.realtime)
        assertEquals(5, a.delayMinutes)
        assertEquals("760", a.line)
        assertEquals("Wörgl Hauptbahnhof", a.direction)
        // Ohne rtTime gibt es keine Echtzeit – niemals „pünktlich“ daraus ableiten
        assertNull(board.departures[1].realtime)
        assertNull(board.departures[1].delayMinutes)
        assertEquals("A", board.departures[1].track)
        // Echtzeit über Mitternacht, Ausfall als Text-Boolean, Produkt als Liste
        val c = board.departures[2]
        assertEquals(3, c.delayMinutes)
        assertTrue(c.cancelled)
        assertEquals("REX 2", c.line)
        assertEquals("2", c.rtTrack)
    }

    @Test fun einzelneAbfahrtAlsObjektUndLeereTafel() {
        val one = """{"Departure":{"name":"Bus 760","time":"16:46:00","date":"2026-11-16"}}"""
        assertEquals(1, (VaoClient.parseBoard(one) as VaoResult.Ok).value.departures.size)
        assertEquals(0, (VaoClient.parseBoard("""{"serverVersion":"2.52.1"}""") as VaoResult.Ok).value.departures.size)
        assertEquals(FailKind.PARSE, (VaoClient.parseBoard("<html>") as VaoResult.Fail).kind)
    }

    @Test fun fehlercodesWerdenEingeordnet() {
        assertEquals(FailKind.AUTH, VaoClient.failFor("API_AUTH", "", 401).kind)
        assertEquals(FailKind.QUOTA, VaoClient.failFor("API_QUOTA", "", 400).kind)
        assertEquals(FailKind.QUOTA, VaoClient.failFor("API_TOO_MANY_REQUESTS", "", 403).kind)
        assertEquals(FailKind.QUOTA, VaoClient.failFor("", "", 429).kind)
        assertEquals(FailKind.SERVER, VaoClient.failFor("INT_TIMEOUT", "", 503).kind)
        assertEquals(FailKind.REQUEST, VaoClient.failFor("SVC_LOC", "", 400).kind)
        val inBody = VaoClient.parseBoard("""{"errorCode":"SVC_NO_RESULT","errorText":"Kein Ergebnis"}""") as VaoResult.Fail
        assertEquals(FailKind.REQUEST, inBody.kind)
    }

    @Test fun anfrageEnthaeltAlleParameter() {
        val vao = FakeVao().apply { boards["at:47:64459"] = listOf(FakeDep("16:46", 5)) }
        var counted = 0
        val client = VaoClient(vao, "gueltig", MemoryVersionCache(), onCountedRequest = { counted++ })
        val r = client.departureBoard("at:47:64459", from, 108, "at:47:1170") as VaoResult.Ok
        assertEquals(5, r.value.departures.single().delayMinutes)
        assertEquals(1, counted)
        val url = vao.requests.last()
        assertTrue(url, url.startsWith("https://routenplaner.verkehrsauskunft.at/vao/restproxy/v1.12.0/departureBoard?"))
        for (part in listOf("accessId=gueltig", "format=json", "id=at%3A47%3A64459", "date=2026-11-16", "time=16%3A41", "duration=108", "direction=at%3A47%3A1170", "passlist=1")) {
            assertTrue("$part fehlt in $url", url.contains(part))
        }
    }

    @Test fun abgeschalteteVersionWirdAutomatischErsetzt() {
        val vao = FakeVao().apply { validVersion = "v1.13.0"; boards["x"] = listOf(FakeDep("16:46")) }
        val cache = MemoryVersionCache().apply { put("v1.12.0", System.currentTimeMillis()) }
        val r = VaoClient(vao, "gueltig", cache).departureBoard("x", from, 60, null)
        assertTrue(r is VaoResult.Ok)
        assertEquals("v1.13.0", cache.get()!!.first)
    }

    @Test fun schluesselNurFuerAeltereVersionWirdErkannt() {
        val vao = FakeVao().apply { keyOnlyFor = "v1.11.0"; boards["x"] = listOf(FakeDep("16:46", 2)) }
        val cache = MemoryVersionCache()
        val client = VaoClient(vao, "gueltig", cache)
        val r = client.departureBoard("x", from, 60, null) as VaoResult.Ok
        assertEquals(2, r.value.departures.single().delayMinutes)
        assertEquals("v1.11.0", cache.get()!!.first)
        // danach direkt mit der passenden Version
        val before = vao.requests.size
        assertTrue(client.departureBoard("x", from, 60, null) is VaoResult.Ok)
        assertEquals(before + 1, vao.requests.size)
        assertTrue(vao.requests.last().contains("/v1.11.0/"))
    }

    @Test fun falscherSchluesselBleibtAbgelehnt() {
        val r = VaoClient(FakeVao(), "falsch", MemoryVersionCache()).departureBoard("x", from, 60, null) as VaoResult.Fail
        assertEquals(FailKind.AUTH, r.kind)
    }

    @Test fun fehlerbilder() {
        val vao = FakeVao()
        val cache = MemoryVersionCache()
        assertEquals(FailKind.NO_KEY, (VaoClient(vao, " ", cache).departureBoard("x", from, 60, null) as VaoResult.Fail).kind)
        assertEquals(0, vao.requests.size)
        val denied = VaoClient(vao, "falsch", cache).departureBoard("x", from, 60, null) as VaoResult.Fail
        assertEquals(FailKind.AUTH, denied.kind)
        // Der Schlüssel taucht in keiner Meldung auf
        assertEquals("access denied for ••• on departureBoard", denied.message)
        vao.offline = true
        assertEquals(FailKind.NETWORK, (VaoClient(vao, "gueltig", cache).departureBoard("x", from, 60, null) as VaoResult.Fail).kind)
        vao.offline = false; vao.errorStatus = 503
        assertEquals(FailKind.SERVER, (VaoClient(vao, "gueltig", cache).departureBoard("x", from, 60, null) as VaoResult.Fail).kind)
        vao.errorStatus = 400; vao.errorCode = "API_QUOTA"
        assertEquals(FailKind.QUOTA, (VaoClient(vao, "gueltig", cache).departureBoard("x", from, 60, null) as VaoResult.Fail).kind)
    }

    @Test fun haltestellensuche() {
        val body = """{"stopLocationOrCoordLocation":[
            {"StopLocation":{"id":"A=1@O=Kundl Gemeindesaal@","extId":"476445900","name":"Kundl Gemeindesaal","altId":["at:47:64459"],"mainMastAltId":["at:47:64459:0:1"]}},
            {"CoordLocation":{"name":"Kundl, Dorfstraße"}},
            {"StopLocation":{"extId":"470117200","name":"Kundl Bahnhof","altId":["at:47:1172"]}}]}"""
        val hits = (VaoClient.parseLocations(body) as VaoResult.Ok).value
        assertEquals(listOf("at:47:64459", "at:47:1172"), hits.map { it.id })
        assertEquals("at:47:64459", VaoClient.stationLevelId("at:47:64459:0:1"))
        assertEquals("476445900", VaoClient.stationLevelId("476445900"))
    }

    @Test fun neuesteVersion() {
        assertEquals(listOf("v1.12.0", "v1.11.0", "v1.9.3"), VaoClient.allVersions("""[{"serviceVersion":"v1.11.0"},{"serviceVersion":"v1.12.0"},{"serviceVersion":"v1.9.3"}]"""))
        assertEquals("v1.12.0", VaoClient.newestVersion("""[{"serviceVersion":"v1.11.0"},{"serviceVersion":"v1.12.0"},{"serviceVersion":"v1.9.3"}]"""))
        assertNull(VaoClient.newestVersion("kaputt"))
    }

    @Test fun aeltereEinstellungenBekommenDieUmstiegsoption() {
        // Stand vor Version 2: ohne Gruppen und ohne die beiden Anschlussfahrten, Vorlauf 15 Min
        val s = Settings()
        val old = s.copy(leadMinutes = 15, connections = s.connections.filter { !it.id.startsWith("hin-kombi") }.map { it.copy(group = "") })
        val json = old.toJson().put("schema", 1)
        val migrated = Settings.fromJson(org.json.JSONObject(json.toString()))
        assertEquals(15, migrated.leadMinutes)
        assertEquals(setOf("hin-gratten", "hin-kombi-rex", "hin-kombi-bus"),
            migrated.connections.filter { it.group.isNotEmpty() }.map { it.id }.toSet())
        // ein zweites Laden ändert nichts mehr
        assertEquals(migrated, Settings.fromJson(org.json.JSONObject(migrated.toJson().toString())))
    }

    @Test fun einstellungenAlsJson() {
        val s = Settings(accessId = "abc", leadMinutes = 40, pinned = mapOf(TripKind.HIN to "hin-gratten"), autoStand = true, nowBar = true, dailyLimit = 250, skipHolidays = false)
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(Settings(), Settings.fromJson(JSONObject("{}")))
    }
}
