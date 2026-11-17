package io.github.freshglitch4j.abfahrt

import io.github.freshglitch4j.abfahrt.core.Http
import io.github.freshglitch4j.abfahrt.core.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/** Eine Abfahrt, wie sie der Testserver ausliefert. */
data class FakeDep(
    val time: String, val delay: Int? = 0, val name: String = "Bus 760", val line: String = "760",
    val direction: String = "Wörgl Hauptbahnhof", val cancelled: Boolean = false, val via: List<String> = listOf("at:47:1170"),
)

/**
 * Stellt die VAO-Schnittstelle nach: Antworten im Aufbau der öffentlichen
 * OpenAPI-Beschreibung (DepartureBoard/Departure/ProductAtStop/Stops).
 */
class FakeVao : Http {
    val requests = mutableListOf<String>()
    val boards = mutableMapOf<String, List<FakeDep>>()
    var offline = false
    var errorStatus = 0
    var errorCode = ""
    var directionFilterSupported = true
    var validVersion = "v1.12.0"
    /** Gesetzt: der Schlüssel gilt nur für diese Version. */
    var keyOnlyFor: String? = null
    var date = "2026-11-16"

    val boardRequests: Int get() = requests.count { it.contains("/departureBoard") }

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun plus(time: String, min: Int): String {
        val t = java.time.LocalTime.parse(time).plusMinutes(min.toLong())
        return "%02d:%02d:00".format(t.hour, t.minute)
    }

    override fun get(url: String): HttpResponse {
        if (offline) throw IOException("Unable to resolve host")
        requests += url
        if (url.endsWith("/vao/restproxy")) {
            return HttpResponse(200, """[{"serviceVersion":"v1.11.0","Decommission":"30.09.2027"},{"serviceVersion":"$validVersion","Decommission":"30.08.2027"}]""")
        }
        val ver = listOf("v1.11.0", validVersion).firstOrNull { url.contains("/$it/") } ?: return HttpResponse(404, "")
        val p = params(url)
        if (p["accessId"] != "gueltig" || (keyOnlyFor != null && keyOnlyFor != ver)) {
            return HttpResponse(401, """{"errorCode":"API_AUTH","errorText":"access denied for ${p["accessId"]} on departureBoard"}""")
        }
        if (errorStatus != 0) return HttpResponse(errorStatus, if (errorCode.isEmpty()) "" else """{"errorCode":"$errorCode","errorText":"Testfehler"}""")
        if (p["direction"] != null && !directionFilterSupported) {
            return HttpResponse(400, """{"errorCode":"SVC_LOC","errorText":"location missing or invalid"}""")
        }
        val all = boards[p["id"]] ?: emptyList()
        val deps = if (p["direction"] != null) all.filter { p["direction"] in it.via } else all
        val arr = JSONArray()
        for (d in deps) {
            val o = JSONObject()
                .put("JourneyDetailRef", JSONObject().put("ref", "1|123|0|81|16112026"))
                .put("JourneyStatus", "P")
                .put("ProductAtStop", JSONObject().put("name", d.name).put("displayNumber", d.line).put("line", d.line).put("catOutL", "Regionalbus"))
                .put("name", d.name).put("type", "ST").put("stop", "Haltestelle").put("stopExtId", "470000000")
                .put("time", plus(d.time, 0)).put("date", date).put("direction", d.direction)
                .put("Stops", JSONObject().put("Stop", JSONArray(d.via.mapIndexed { i, id ->
                    JSONObject().put("name", "Halt $i").put("id", "A=1@O=Halt@L=47000000$i@i=A×$id@").put("extId", "47000000$i")
                })))
            if (d.delay != null && !d.cancelled) o.put("rtTime", plus(d.time, d.delay)).put("rtDate", date).put("prognosisType", "PROGNOSED")
            if (d.cancelled) o.put("cancelled", true)
            arr.put(o)
        }
        return HttpResponse(200, JSONObject().put("Departure", arr).put("serverVersion", "2.52.1").put("requestId", "abfahrt").toString())
    }
}
