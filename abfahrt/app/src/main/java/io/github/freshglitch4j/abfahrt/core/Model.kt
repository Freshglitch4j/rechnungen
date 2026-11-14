package io.github.freshglitch4j.abfahrt.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/** Alle Zeiten der App beziehen sich auf österreichische Ortszeit – wie die VAO-Schnittstelle. */
val ZONE: ZoneId = ZoneId.of("Europe/Vienna")

enum class TripKind(val title: String) {
    HIN("Hinfahrt"),
    HEIM("Heimfahrt"),
}

/** Eine überwachte regelmäßige Fahrt. */
data class Connection(
    val id: String,
    val kind: TripKind,
    /** Anzeigename, z. B. „Bus 760“ oder „REX 2“. */
    val label: String,
    val stopName: String,
    /** Globale Haltestellen-ID (IFOPT), z. B. at:47:64459 – bleibt über Fahrplanwechsel stabil. */
    val stopId: String,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    /** Linienfilter (z. B. „760“ oder „REX“); leer = jede Linie. */
    val line: String = "",
    val towardsName: String = "",
    /** Haltestelle im weiteren Fahrtverlauf; grenzt die Richtung ein. */
    val towardsId: String = "",
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("label", label)
        .put("stopName", stopName)
        .put("stopId", stopId)
        .put("time", time.toString())
        .put("days", JSONArray(days.map { it.value }.sorted()))
        .put("line", line)
        .put("towardsName", towardsName)
        .put("towardsId", towardsId)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): Connection {
            val days = o.optJSONArray("days") ?: JSONArray()
            return Connection(
                id = o.getString("id"),
                kind = TripKind.valueOf(o.getString("kind")),
                label = o.optString("label"),
                stopName = o.optString("stopName"),
                stopId = o.optString("stopId"),
                time = LocalTime.parse(o.getString("time")),
                days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                line = o.optString("line"),
                towardsName = o.optString("towardsName"),
                towardsId = o.optString("towardsId"),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

data class Settings(
    val connections: List<Connection> = defaultConnections(),
    /** Hauptschalter: aus = keine Anzeige, keine Abfragen. */
    val active: Boolean = true,
    /** So viele Minuten vor der ersten Abfahrt erscheint die Anzeige. */
    val leadMinutes: Int = 30,
    /** Persönlicher VAO-Zugangsschlüssel (accessId). Leer = nur Fahrplanzeiten. */
    val accessId: String = "",
    /** Tageskontingent an Abfragen (VAO Start: 100). */
    val dailyLimit: Int = 100,
    /** Bevorzugte Fahrt je Richtung (Verbindungs-ID); fehlt = die jeweils nächste. */
    val pinned: Map<TripKind, String> = emptyMap(),
    val skipHolidays: Boolean = true,
    /** Standanzeige bei Beginn automatisch über dem Sperrbildschirm einschalten. */
    val autoStand: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("connections", JSONArray(connections.map { it.toJson() }))
        .put("active", active)
        .put("leadMinutes", leadMinutes)
        .put("accessId", accessId)
        .put("dailyLimit", dailyLimit)
        .put("pinned", JSONObject().also { p -> pinned.forEach { (k, v) -> p.put(k.name, v) } })
        .put("skipHolidays", skipHolidays)
        .put("autoStand", autoStand)

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val arr = o.optJSONArray("connections")
            val conns = if (arr == null) defaultConnections()
            else (0 until arr.length()).map { Connection.fromJson(arr.getJSONObject(it)) }
            val pinned = mutableMapOf<TripKind, String>()
            o.optJSONObject("pinned")?.let { p ->
                for (k in TripKind.entries) p.optString(k.name).takeIf { it.isNotEmpty() }?.let { pinned[k] = it }
            }
            return Settings(
                connections = conns,
                active = o.optBoolean("active", true),
                leadMinutes = o.optInt("leadMinutes", 30).coerceIn(5, 120),
                accessId = o.optString("accessId").trim(),
                dailyLimit = o.optInt("dailyLimit", 100).coerceIn(10, 100000),
                pinned = pinned,
                skipHolidays = o.optBoolean("skipHolidays", true),
                autoStand = o.optBoolean("autoStand", false),
            )
        }
    }
}

private val MO_FR = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val MO_DO = MO_FR - DayOfWeek.FRIDAY

const val WOERGL_HBF = "at:47:1170"

/**
 * Voreinstellung. Haltestellen-IDs, Linien und Zeiten sind gegen die
 * offiziellen Soll-Fahrplandaten (VVT, ÖBB – Fahrplanjahr 2026) geprüft.
 */
fun defaultConnections(): List<Connection> = listOf(
    Connection(
        id = "hin-moosegg", kind = TripKind.HIN, label = "Bus 760",
        stopName = "Kastengstatt Moosegg", stopId = "at:47:65761",
        time = LocalTime.of(6, 33), days = MO_FR, line = "760",
        towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
    ),
    Connection(
        // Die 06:37 ist in den Soll-Daten nicht enthalten (dort 06:32 Linie 4056
        // und 06:42 Linie 4055). Ohne Linienfilter, damit die App die Fahrt
        // anhand der Uhrzeit sucht und Abweichungen anzeigt.
        id = "hin-gratten", kind = TripKind.HIN, label = "Bus",
        stopName = "Kirchbichl Grattenbrücke", stopId = "at:47:65724",
        time = LocalTime.of(6, 37), days = MO_FR, line = "",
        towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
    ),
    Connection(
        id = "heim-bus", kind = TripKind.HEIM, label = "Bus 760",
        stopName = "Kundl Gemeindesaal", stopId = "at:47:64459",
        time = LocalTime.of(16, 46), days = MO_DO, line = "760",
        towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
    ),
    Connection(
        id = "heim-rex", kind = TripKind.HEIM, label = "REX 2",
        stopName = "Kundl Bahnhof", stopId = "at:47:1172",
        time = LocalTime.of(16, 59), days = MO_DO, line = "REX",
        towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
    ),
    Connection(
        id = "heim-rex-fr", kind = TripKind.HEIM, label = "REX 2",
        stopName = "Kundl Bahnhof", stopId = "at:47:1172",
        time = LocalTime.of(12, 29), days = setOf(DayOfWeek.FRIDAY), line = "REX",
        towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
    ),
)
