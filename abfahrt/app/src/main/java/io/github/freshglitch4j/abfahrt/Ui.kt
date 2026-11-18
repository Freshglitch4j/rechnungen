package io.github.freshglitch4j.abfahrt

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.freshglitch4j.abfahrt.core.Display
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Row
import java.time.LocalDateTime

/** Kleine Helfer, um die Oberfläche ohne XML aufzubauen. */
object Ui {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DIM = 0xFFB0B0B0.toInt()
    const val FAINT = 0xFF707070.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun text(c: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = WHITE, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /**
     * „16:46 → 16:51“: die Planzeit gedämpft, die erwartete Abfahrt hell und fett –
     * so ist auf einen Blick klar, welche Zeit gilt. Ohne Echtzeit steht nur die Planzeit da.
     */
    fun times(r: Row, expectedColor: Int = WHITE): CharSequence {
        val expected = r.expected ?: return Fmt.time(r.planned)
        val plan = Fmt.time(r.planned) + " → "
        return SpannableString(plan + Fmt.time(expected)).apply {
            setSpan(ForegroundColorSpan(DIM), 0, plan.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.62f), 0, plan.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), plan.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(expectedColor), plan.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Dünne Trennlinie. */
    fun divider(c: Context, marginDp: Int): View = View(c).apply {
        setBackgroundColor(0xFF3A3A3A.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)).apply {
            topMargin = dp(c, marginDp); bottomMargin = dp(c, marginDp)
        }
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun rowLayout(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun button(c: Context, label: String, onClick: () -> Unit): Button = Button(c).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun spacer(c: Context, heightDp: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp))
    }

    fun section(c: Context, title: String): TextView = text(c, title.uppercase(), 12f, Notifier.GREEN, true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(c, 22), 0, dp(c, 6))
    }

    /**
     * Die Abfahrten als große, ruhige Zeilen: Linie und Haltestelle, Plan → erwartet,
     * Status in Farbe, verbleibende Zeit. [scale] vergrößert alles für die Standanzeige.
     */
    fun departures(c: Context, d: Display, now: LocalDateTime, scale: Float = 1f): LinearLayout {
        val col = column(c)
        // Die nächste Fahrt groß, weitere kleiner und durch eine Linie abgetrennt.
        d.primary?.let { col.addView(departure(c, it, now, scale * 1.15f)) }
        for ((i, r) in d.others.withIndex()) {
            col.addView(if (i == 0) divider(c, (16 * scale).toInt()) else spacer(c, (12 * scale).toInt()))
            col.addView(departure(c, r, now, scale * 0.72f).apply { alpha = 0.85f })
        }
        return col
    }

    private fun departure(c: Context, r: Row, now: LocalDateTime, scale: Float): LinearLayout {
        val col = column(c)
        if (r.gone) col.alpha = 0.45f
        val color = Notifier.color(r)
        col.addView(text(c, "${r.lineLabel} · ${Fmt.where(r)}", 15f * scale, DIM))
        col.addView(text(c, times(r, color), 38f * scale, if (r.hasRealtime) WHITE else DIM, !r.hasRealtime).apply { fontFeatureSettings = "tnum" })
        // Status in Farbe, dahinter die verbleibende Zeit – als ein Text, damit er sauber umbricht.
        val status = r.statusText.uppercase()
        val cd = Fmt.countdown(r, now)
        val line = SpannableString(if (cd.isEmpty()) status else "$status   $cd").apply {
            setSpan(ForegroundColorSpan(color), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(text(c, line, 17f * scale, WHITE))
        if (r.note.isNotEmpty()) col.addView(text(c, r.note, 13f * scale, DIM))
        return col
    }
}
