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
        prefs.edit().putString("quotaDate", today.toString()).putInt("quotaUsed", usedToday(today) + 1).apply()
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
