package io.github.freshglitch4j.abfahrt

import android.content.Context
import android.content.SharedPreferences
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.VersionCache
import io.github.freshglitch4j.abfahrt.core.ZONE
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Dauerhaft gespeicherte Daten: Einstellungen, Tageszähler, Protokoll. */
class Store(context: Context) : VersionCache {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("abfahrt", Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "protokoll.txt")

    var settings: Settings
        get() = prefs.getString("settings", null)?.let {
            try { Settings.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: Settings()
        set(value) { prefs.edit().putString("settings", value.toJson().toString()).apply() }

    // ---- Tageskontingent -------------------------------------------------

    fun usedToday(today: LocalDate = LocalDate.now(ZONE)): Int =
        if (prefs.getString("quotaDate", "") == today.toString()) prefs.getInt("quotaUsed", 0) else 0

    fun countRequest(today: LocalDate = LocalDate.now(ZONE)) {
        val used = usedToday(today) + 1
        val history = historyJson().put(today.toString(), used)
        // nur die letzten 5 Wochen behalten
        history.keys().asSequence().toList().filter { it < today.minusDays(35).toString() }.forEach { history.remove(it) }
        prefs.edit().putString("quotaDate", today.toString()).putInt("quotaUsed", used)
            .putString("quotaHistory", history.toString()).apply()
    }

    private fun historyJson(): JSONObject =
        try { JSONObject(prefs.getString("quotaHistory", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Merkt, dass an [day] eine Anzeige ausgeblendet wurde (für die Auswertung des Verbrauchs). */
    fun countDismiss(day: LocalDate = LocalDate.now(ZONE)) {
        val h = try { JSONObject(prefs.getString("dismissHistory", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        h.put(day.toString(), h.optInt(day.toString(), 0) + 1)
        h.keys().asSequence().toList().filter { it < day.minusDays(35).toString() }.forEach { h.remove(it) }
        prefs.edit().putString("dismissHistory", h.toString()).apply()
    }

    fun dismissedOn(day: LocalDate): Int =
        try { JSONObject(prefs.getString("dismissHistory", "{}") ?: "{}").optInt(day.toString(), 0) } catch (e: Exception) { 0 }

    /** Tatsächlicher Verbrauch der letzten Tage (ohne heute), neueste zuerst; nur Tage mit Abfragen. */
    fun history(today: LocalDate = LocalDate.now(ZONE), days: Int = 7): List<Pair<LocalDate, Int>> {
        val h = historyJson()
        return (1..days).map { today.minusDays(it.toLong()) }
            .mapNotNull { d -> h.optInt(d.toString(), 0).takeIf { it > 0 }?.let { d to it } }
    }

    /** Der Server hat das Kontingent als erschöpft gemeldet. */
    fun markExhausted(limit: Int, today: LocalDate = LocalDate.now(ZONE)) {
        prefs.edit().putString("quotaDate", today.toString()).putInt("quotaUsed", limit).apply()
    }

    fun budgetLeft(today: LocalDate = LocalDate.now(ZONE)): Int = settings.dailyLimit - usedToday(today)

    // ---- „Heute ausblenden“ ----------------------------------------------

    var dismissedKey: String
        get() = prefs.getString("dismissed", "") ?: ""
        set(value) { prefs.edit().putString("dismissed", value).apply() }

    // ---- Richtungsfilter des Servers ---------------------------------------

    /** null = noch nicht bekannt, ob der Server die Richtungs-ID akzeptiert. */
    var directionFilterWorks: Boolean?
        get() = if (prefs.contains("dirOk")) prefs.getBoolean("dirOk", true) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove("dirOk") else putBoolean("dirOk", value) }.apply()
        }

    // ---- Schnittstellenversion ---------------------------------------------

    override fun get(): Pair<String, Long>? {
        val v = prefs.getString("apiVersion", null) ?: return null
        return v to prefs.getLong("apiVersionAt", 0)
    }

    override fun put(version: String, atMillis: Long) {
        prefs.edit().putString("apiVersion", version).putLong("apiVersionAt", atMillis).apply()
    }

    override fun clear() {
        prefs.edit().remove("apiVersion").remove("apiVersionAt").apply()
    }

    // ---- Protokoll -----------------------------------------------------------

    @Synchronized
    fun log(message: String) {
        try {
            if (logFile.length() > 96_000) {
                val keep = logFile.readText().takeLast(48_000).substringAfter('\n')
                logFile.writeText(keep)
            }
            logFile.appendText(LocalDateTime.now(ZONE).format(STAMP) + "  " + message + "\n")
        } catch (e: Exception) {
            // Protokoll ist nie kritisch.
        }
    }

    @Synchronized
    fun readLog(): String = try { if (logFile.exists()) logFile.readText() else "" } catch (e: Exception) { "" }

    @Synchronized
    fun clearLog() { logFile.delete() }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss")
    }
}
