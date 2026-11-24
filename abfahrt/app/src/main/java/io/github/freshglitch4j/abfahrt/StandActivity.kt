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
 * Anzeigefenster läuft. Gedacht für „Handy liegt vor mir“.
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
        root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            // Gedimmt wie ein Always-On-Display; Tippen macht 15 Sekunden lang hell.
            setOnClickListener {
                brightUntil = System.currentTimeMillis() + 15_000
                applyBrightness()
            }
        }
        setContentView(root)
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

    private var brightUntil = 0L

    private fun applyBrightness() {
        val bright = System.currentTimeMillis() < brightUntil
        window.attributes = window.attributes.apply {
            screenBrightness = if (bright) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else DIM_BRIGHTNESS
        }
    }

    private var scroll: android.widget.ScrollView? = null

    private fun render() {
        applyBrightness()
        val d = Live.display
        val now = Live.clock()
        val keepY = scroll?.scrollY ?: 0
        root.removeAllViews()
        root.fitsSystemWindows = true
        val col = Ui.column(this)
        // Inhalt wandert minütlich um ein paar Pixel – schont das OLED-Display.
        val shift = (now.minute % 5) * Ui.dp(this, 4)
        col.setPadding(Ui.dp(this, 24) + shift, Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 12))

        if (d == null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            col.addView(Ui.text(this, "Keine aktuelle Abfahrt", 24f, Ui.WHITE, true))
            val store = Store(this)
            val next = Schedule.upcoming(now, store.settings).firstOrNull { it.start.isAfter(now) && it.key != store.dismissedKey }
            if (next != null) {
                col.addView(Ui.spacer(this, 8))
                col.addView(Ui.text(this, "Nächste Anzeige: ${next.kind.title} ${DepartureWidget.dayLabel(next, now.toLocalDate())} ab ${Fmt.time(next.start)}", 16f, Ui.DIM))
            }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        add("Schließen") { finish() }
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

        /** Displayhelligkeit der gedimmten Standanzeige (0 = dunkelste Stufe, 1 = voll). */
        const val DIM_BRIGHTNESS = 0.05f
    }
}
