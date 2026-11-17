package io.github.freshglitch4j.abfahrt.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Zugriff auf die offizielle REST-Schnittstelle der Verkehrsauskunft
 * Österreich (VAO), Dienst „departureBoard“. Grundlage: Handbuch VAO ReST API
 * (Stand 28.01.2026) und die öffentliche OpenAPI-Beschreibung.
 */

data class HttpResponse(val status: Int, val body: String)

fun interface Http {
    @Throws(IOException::class)
    fun get(url: String): HttpResponse
}

class UrlConnectionHttp(private val connectTimeoutMs: Int = 8000, private val readTimeoutMs: Int = 12000) : Http {
    override fun get(url: String): HttpResponse {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Abfahrt-Android/1.0")
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(status, body)
        } finally {
            c.disconnect()
        }
    }
}

data class Departure(
    val planned: LocalDateTime,
    /** Aktuell erwartete Abfahrt laut Echtzeit; null = es liegen keine Echtzeitdaten vor. */
    val realtime: LocalDateTime?,
    val name: String,
    val line: String,
    val category: String,
    val direction: String,
    val cancelled: Boolean,
    val track: String?,
    val rtTrack: String?,
    val stopName: String,
    /** Weitere Halte der Fahrt (Namen und IDs), sofern mitgeliefert. */
    val passStops: List<PassStop> = emptyList(),
) {
    val delayMinutes: Int?
        get() = realtime?.let { java.time.Duration.between(planned, it).toMinutes().toInt() }
}

data class PassStop(val name: String, val ids: String)

data class Board(val departures: List<Departure>)

enum class FailKind {
    /** Kein Zugangsschlüssel hinterlegt. */ NO_KEY,
    /** Schlüssel ungültig oder für den Dienst nicht freigeschaltet. */ AUTH,
    /** Tageskontingent erschöpft oder zu viele Anfragen. */ QUOTA,
    /** Keine Verbindung zum Server (offline, Zeitüberschreitung). */ NETWORK,
    /** Server meldet einen Fehler (5xx). */ SERVER,
    /** Anfrage wurde abgelehnt (Parameter, Haltestelle unbekannt …). */ REQUEST,
    /** Antwort nicht lesbar. */ PARSE,
}

sealed class VaoResult<out T> {
    data class Ok<T>(val value: T) : VaoResult<T>()
    data class Fail(val kind: FailKind, val message: String, val code: String = "") : VaoResult<Nothing>()
}

data class StopHit(val name: String, val id: String)

/** Merkt sich die aktuell gültige Schnittstellenversion (VAO schaltet alte Versionen ab). */
interface VersionCache {
    fun get(): Pair<String, Long>?
    fun put(version: String, atMillis: Long)
    fun clear()
}

class MemoryVersionCache : VersionCache {
    private var v: Pair<String, Long>? = null
    override fun get() = v
    override fun put(version: String, atMillis: Long) { v = version to atMillis }
    override fun clear() { v = null }
}

class VaoClient(
    private val http: Http,
    private val accessId: String,
    private val versions: VersionCache,
    private val base: String = BASE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Wird für jede Anfrage aufgerufen, die das Kontingent belastet. */
    private val onCountedRequest: () -> Unit = {},
) {
    companion object {
        const val BASE = "https://routenplaner.verkehrsauskunft.at/vao/restproxy"
        const val FALLBACK_VERSION = "v1.12.0"
        const val VERSION_TTL_MS = 7L * 24 * 3600 * 1000

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Wählt aus der Versionsliste des Servers die höchste Version. */
        fun newestVersion(json: String): String? {
            val arr = try { JSONArray(json) } catch (e: Exception) { return null }
            var best: String? = null
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i)?.optString("serviceVersion").orEmpty()
                if (!Regex("""v\d+(\.\d+)*""").matches(v)) continue
                if (best == null || compareVersions(v, best) > 0) best = v
            }
            return best
        }

        /** Alle Versionen der Serverliste, neueste zuerst. */
        fun allVersions(json: String): List<String> {
            val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
            return (0 until arr.length())
                .map { arr.optJSONObject(it)?.optString("serviceVersion").orEmpty() }
                .filter { Regex("""v\d+(\.\d+)*""").matches(it) }
                .distinct()
                .sortedWith { a, b -> compareVersions(b, a) }
        }

        fun compareVersions(a: String, b: String): Int {
            val pa = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }

        fun parseBoard(body: String): VaoResult<Board> {
            val root = try { JSONObject(body) } catch (e: Exception) {
                return VaoResult.Fail(FailKind.PARSE, "Antwort ist kein JSON")
            }
            errorOf(root)?.let { return it }
            val deps = mutableListOf<Departure>()
            for (d in objects(root.opt("Departure"))) {
                try {
                    parseDeparture(d)?.let { deps += it }
                } catch (e: Exception) {
                    // Einzelne unlesbare Einträge überspringen, den Rest verwenden.
                }
            }
            return VaoResult.Ok(Board(deps))
        }

        private fun parseDeparture(d: JSONObject): Departure? {
            val date = d.optString("date")
            val time = d.optString("time")
            if (date.isEmpty() || time.isEmpty()) return null
            val planned = LocalDateTime.of(LocalDate.parse(date), parseTime(time))
            val rtTime = d.optString("rtTime")
            val realtime = if (rtTime.isEmpty()) null else {
                val rtDate = d.optString("rtDate").ifEmpty { date }
                LocalDateTime.of(LocalDate.parse(rtDate), parseTime(rtTime))
            }
            val product = d.optJSONObject("ProductAtStop")
                ?: objects(d.opt("Product")).firstOrNull()
            val name = d.optString("name").ifEmpty { product?.optString("name").orEmpty() }.trim()
            val line = product?.let { p ->
                p.optString("displayNumber").ifEmpty { p.optString("line") }.ifEmpty { p.optString("num") }
            }.orEmpty().trim()
            val category = product?.let { p -> p.optString("catOutL").ifEmpty { p.optString("catOutS") } }.orEmpty().trim()
            val stops = objects(d.optJSONObject("Stops")?.opt("Stop")).map { s ->
                val ids = buildString {
                    append(s.optString("id")).append(' ').append(s.optString("extId")).append(' ')
                    strings(s.opt("altId")).forEach { append(it).append(' ') }
                    strings(s.opt("mainMastAltId")).forEach { append(it).append(' ') }
                }
                PassStop(s.optString("name"), ids)
            }
            return Departure(
                planned = planned,
                realtime = realtime,
                name = name,
                line = line,
                category = category,
                direction = d.optString("direction").trim(),
                cancelled = bool(d.opt("cancelled")),
                track = d.optString("track").ifEmpty { null },
                rtTrack = d.optString("rtTrack").ifEmpty { null },
                stopName = d.optString("stop").trim(),
                passStops = stops,
            )
        }

        fun parseLocations(body: String): VaoResult<List<StopHit>> {
            val root = try { JSONObject(body) } catch (e: Exception) {
                return VaoResult.Fail(FailKind.PARSE, "Antwort ist kein JSON")
            }
            errorOf(root)?.let { return it }
            val hits = mutableListOf<StopHit>()
            for (entry in objects(root.opt("stopLocationOrCoordLocation"))) {
                val s = entry.optJSONObject("StopLocation") ?: continue
                val alt = strings(s.opt("mainMastAltId")).firstOrNull()
                    ?: strings(s.opt("altId")).firstOrNull()
                    ?: s.optString("extId")
                if (alt.isNullOrEmpty()) continue
                hits += StopHit(s.optString("name"), stationLevelId(alt))
            }
            return VaoResult.Ok(hits)
        }

        /** at:47:64459:0:1 (Steig) → at:47:64459 (Haltestelle). */
        fun stationLevelId(id: String): String {
            val parts = id.split(':')
            return if (parts.size > 3 && parts[0].length == 2) parts.take(3).joinToString(":") else id
        }

        private fun errorOf(root: JSONObject): VaoResult.Fail? {
            val code = root.optString("errorCode")
            if (code.isEmpty()) return null
            return failFor(code, root.optString("errorText"), 0)
        }

        fun failFor(code: String, text: String, status: Int): VaoResult.Fail {
            val kind = when {
                code == "API_AUTH" || status == 401 -> FailKind.AUTH
                code == "API_QUOTA" || code == "API_TOO_MANY_REQUESTS" || status == 429 -> FailKind.QUOTA
                code.startsWith("INT_") || code.startsWith("NT_") || code == "SVC_FAILED_SEARCH" || status >= 500 -> FailKind.SERVER
                // 403 ohne Fehlercode: laut Handbuch je nach Version „Zugriff verweigert“ oder „too many requests“.
                status == 403 -> FailKind.AUTH
                else -> FailKind.REQUEST
            }
            return VaoResult.Fail(kind, text.ifEmpty { code.ifEmpty { "HTTP $status" } }, code)
        }

        private fun parseTime(s: String): LocalTime {
            val p = s.split(':')
            return LocalTime.of(p[0].toInt(), p[1].toInt())
        }

        private fun bool(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }

        /** Die Schnittstelle liefert ein Element je nach Anzahl als Objekt oder als Liste. */
        private fun objects(v: Any?): List<JSONObject> = when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
            is JSONObject -> listOf(v)
            else -> emptyList()
        }

        private fun strings(v: Any?): List<String> = when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull { i ->
                when (val e = v.opt(i)) {
                    is String -> e
                    is JSONObject -> e.optString("value").ifEmpty { e.optString("$") }.ifEmpty { null }
                    else -> null
                }
            }
            is String -> listOf(v)
            is JSONObject -> listOfNotNull(v.optString("value").ifEmpty { v.optString("$") }.ifEmpty { null })
            else -> emptyList()
        }
    }

    private fun version(): String {
        val cached = versions.get()
        if (cached != null && nowMillis() - cached.second < VERSION_TTL_MS) return cached.first
        val fresh = try {
            val r = http.get(base)
            if (r.status == 200) newestVersion(r.body) else null
        } catch (e: IOException) {
            null
        }
        if (fresh != null) {
            versions.put(fresh, nowMillis())
            return fresh
        }
        // Liste nicht erreichbar: letzte bekannte bzw. die bei der Entwicklung aktuelle Version.
        return cached?.first ?: FALLBACK_VERSION
    }

    /** Der Server nennt in Fehlermeldungen den Schlüssel – er darf weder ins Protokoll noch auf den Sperrbildschirm. */
    private fun <T> call(service: String, params: List<Pair<String, String>>, parse: (String) -> VaoResult<T>): VaoResult<T> {
        val r = callRaw(service, params, parse)
        return if (r is VaoResult.Fail && accessId.isNotBlank()) r.copy(message = r.message.replace(accessId, "•••")) else r
    }

    private fun <T> callRaw(service: String, params: List<Pair<String, String>>, parse: (String) -> VaoResult<T>): VaoResult<T> {
        if (accessId.isBlank()) return VaoResult.Fail(FailKind.NO_KEY, "Kein VAO-Zugangsschlüssel hinterlegt")
        val query = (listOf("accessId" to accessId, "format" to "json", "lang" to "de", "requestId" to "abfahrt") + params)
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        var ver = version()
        var result = try {
            once(ver, service, query, parse) ?: run {
                // Version abgeschaltet → Liste neu laden und einmal wiederholen.
                versions.clear()
                ver = version()
                once(ver, service, query, parse)
                    ?: VaoResult.Fail(FailKind.SERVER, "Schnittstellenversion $ver nicht verfügbar")
            }
        } catch (e: IOException) {
            return VaoResult.Fail(FailKind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        // Ein Schlüssel kann für eine bestimmte Version ausgestellt sein (z. B. v1.11.0):
        // bei Ablehnung die übrigen Versionen der Serverliste probieren und die passende merken.
        if (result is VaoResult.Fail && result.kind == FailKind.AUTH) {
            val others = try {
                val r = http.get(base)
                if (r.status == 200) allVersions(r.body) else emptyList()
            } catch (e: IOException) {
                emptyList()
            }.filter { it != ver }
            for (other in others) {
                val r = try { once(other, service, query, parse) } catch (e: IOException) { null } ?: continue
                if (r is VaoResult.Fail && r.kind == FailKind.AUTH) continue
                versions.put(other, nowMillis())
                return r
            }
        }
        return result
    }

    /** Eine Anfrage an eine bestimmte Version; null = Version gibt es nicht (404). */
    private fun <T> once(ver: String, service: String, query: String, parse: (String) -> VaoResult<T>): VaoResult<T>? {
        val resp = http.get("$base/$ver/$service?$query")
        if (resp.status == 404) return null
        onCountedRequest()
        if (resp.status in 200..299) return parse(resp.body)
        val root = try { JSONObject(resp.body) } catch (e: Exception) { null }
        if (root != null && root.optString("errorCode").isNotEmpty()) {
            return failFor(root.optString("errorCode"), root.optString("errorText"), resp.status)
        }
        // Fehler kann auch als XML kommen.
        val code = Regex("""errorCode="([^"]*)"""").find(resp.body)?.groupValues?.get(1).orEmpty()
        val text = Regex("""errorText="([^"]*)"""").find(resp.body)?.groupValues?.get(1).orEmpty()
        return failFor(code, text, resp.status)
    }

    /**
     * Abfahrten an [stopId] ab [from] für [durationMin] Minuten. Mit [towardsId]
     * liefert der Server nur Fahrten, die später dort halten.
     */
    fun departureBoard(stopId: String, from: LocalDateTime, durationMin: Int, towardsId: String?): VaoResult<Board> {
        val p = mutableListOf(
            "id" to stopId,
            "date" to from.toLocalDate().toString(),
            "time" to "%02d:%02d".format(from.hour, from.minute),
            "duration" to durationMin.coerceIn(1, 1439).toString(),
            "passlist" to "1",
        )
        if (!towardsId.isNullOrEmpty()) p += "direction" to towardsId
        return call("departureBoard", p, ::parseBoard)
    }

    /** Haltestellensuche für die Einstellungen. */
    fun searchStops(text: String): VaoResult<List<StopHit>> =
        call("location.name", listOf("input" to text, "type" to "S", "maxNo" to "8"), ::parseLocations)
}
