package io.github.freshglitch4j.abfahrt

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Schedule

/**
 * Standanzeige: schwarzer Vollbildschirm über dem Sperrbildschirm, der die
 * Abfahrten groß zeigt und das Display eingeschaltet lässt, solange ein
 * Anzeigefenster läuft (außer in der Tasche). Helligkeit wie im System eingestellt.
 */
class StandActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private val onChange: () -> Unit = { handler.post { render() } }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        alive = true
        getSystemService(android.app.NotificationManager::class.java).cancel(Notifier.ID_STAND)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        setContentView(root)
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    /** In der Tasche (Näherungssensor verdeckt) darf das Display normal ausgehen. */
    private var covered = false
    private val proximity = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            covered = e.values[0] < minOf(e.sensor.maximumRange, 3f)
            keepOn(Live.display != null)
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun keepOn(active: Boolean) {
        if (active && !covered) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(proximity, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(proximity)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        Live.addListener(onChange)
        handler.post(ticker)
        // Beim Öffnen gleich den aktuellen Stand holen.
        if (Live.display != null) LiveService.start(this, Controller.ACTION_TICK)
    }

    override fun onStop() {
        Live.removeListener(onChange)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    /** Wurde in dieser Standanzeige schon ein Fenster gezeigt? Dann nach dessen Ende schließen. */
    private var hadDisplay = false

    private var scroll: android.widget.ScrollView? = null

    private fun render() {
        val d = Live.display
        if (d != null) hadDisplay = true
        else if (hadDisplay) { finish(); return }
        val now = Live.clock()
        val keepY = scroll?.scrollY ?: 0
        root.removeAllViews()
        root.fitsSystemWindows = true
        val col = Ui.column(this)
        // Inhalt wandert minütlich um ein paar Pixel – schont das OLED-Display.
        val shift = (now.minute % 5) * Ui.dp(this, 4)
        col.setPadding(Ui.dp(this, 24) + shift, Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 12))

        if (d == null) {
            keepOn(false)
            col.addView(Ui.text(this, "Keine aktuelle Abfahrt", 24f, Ui.WHITE, true))
            val store = Store(this)
            val next = Schedule.upcoming(now, store.settings).firstOrNull { it.start.isAfter(now) && it.key != store.dismissedKey }
            if (next != null) {
                col.addView(Ui.spacer(this, 8))
                col.addView(Ui.text(this, "Nächste Anzeige: ${next.kind.title} ${DepartureWidget.dayLabel(next, now.toLocalDate())} ab ${Fmt.time(next.start)}", 16f, Ui.DIM))
            }
        } else {
            keepOn(true)
            if (d.demo) col.addView(Ui.text(this, "DEMO – ERFUNDENE WERTE", 13f, Notifier.YELLOW, true).apply { letterSpacing = 0.1f })
            col.addView(Ui.departures(this, d, now, 1.3f, countdown = false, compact = true))
        }

        // Aktuelle Uhrzeit groß, oben mittig, durch eine Linie von den Abfahrten getrennt.
        val page = Ui.column(this)
        page.addView(Ui.text(this, Fmt.time(now), 56f, Ui.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            fontFeatureSettings = "tnum"
            setPadding(shift, Ui.dp(this@StandActivity, 16) + shift, 0, 0)
        })
        // Darunter klein: wie aktuell die Daten sind.
        if (d != null) page.addView(Ui.text(this, updatedText(d.asOf, now), 14f, Ui.DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(shift, 0, 0, 0)
        })
        page.addView(Ui.divider(this, 14).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = Ui.dp(this@StandActivity, 24); rightMargin = Ui.dp(this@StandActivity, 24)
            }
        })
        page.addView(col)

        // Knöpfe fest am unteren Rand – immer sichtbar, auch wenn viele Fahrten angezeigt werden.
        val buttons = Ui.rowLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            setPadding(Ui.dp(this@StandActivity, 20), Ui.dp(this@StandActivity, 6), Ui.dp(this@StandActivity, 20), Ui.dp(this@StandActivity, 8))
        }
        fun add(label: String, onClick: () -> Unit) {
            buttons.addView(
                Ui.button(this, label, onClick).apply {
                    setSingleLine()
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, paddingTop, 0, paddingBottom)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Ui.dp(this@StandActivity, 4); rightMargin = Ui.dp(this@StandActivity, 4)
                },
            )
        }
        add("Schließen") {
            // für dieses Anzeigefenster nicht mehr von selbst öffnen
            Live.window?.let { Store(this).standClosedKey = it.key }
            finish()
        }
        if (d != null) add("Aktualisieren") { LiveService.start(this, Controller.ACTION_REFRESH) }

        val sv = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        scroll = sv
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (keepY > 0) sv.post { sv.scrollTo(0, keepY) }
    }

    companion object {
        /** „gerade aktualisiert" bzw. „aktualisiert vor 3 Min". */
        fun updatedText(asOf: java.time.LocalDateTime?, now: java.time.LocalDateTime): String {
            if (asOf == null) return "noch nicht aktualisiert"
            val min = java.time.Duration.between(asOf, now).toMinutes()
            return when {
                min < 1 -> "gerade aktualisiert"
                min < 60 -> "aktualisiert vor $min Min"
                else -> "aktualisiert um ${Fmt.time(asOf)}"
            }
        }

        /** Standanzeige ist gerade geöffnet (auch hinter dem ausgeschalteten Display). */
        @Volatile var alive = false
    }
}
