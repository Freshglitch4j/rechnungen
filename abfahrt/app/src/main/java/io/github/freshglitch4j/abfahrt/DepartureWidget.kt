package io.github.freshglitch4j.abfahrt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.freshglitch4j.abfahrt.core.Display
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.Window
import java.time.format.TextStyle
import java.util.Locale

/** Startbildschirm-Widget: zeigt dasselbe wie die Benachrichtigung, sonst die nächste geplante Anzeige. */
class DepartureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    companion object {
        private val ROWS = intArrayOf(R.id.row1, R.id.row2, R.id.row3)
        private val LABELS = intArrayOf(R.id.label1, R.id.label2, R.id.label3)
        private val TIMES = intArrayOf(R.id.times1, R.id.times2, R.id.times3)
        private val SUBS = intArrayOf(R.id.sub1, R.id.sub2, R.id.sub3)
        private val STATUS = intArrayOf(R.id.status1, R.id.status2, R.id.status3)

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val name = ComponentName(context, DepartureWidget::class.java)
            if (manager.getAppWidgetIds(name).isEmpty()) return
            manager.updateAppWidget(name, render(context, Live.display))
        }

        fun dayLabel(w: Window, today: java.time.LocalDate): String = when (w.date) {
            today -> "heute"
            today.plusDays(1) -> "morgen"
            else -> w.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix(".")
        }

        fun render(context: Context, d: Display?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget)
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(
                    context, 30, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            for (i in ROWS.indices) v.setViewVisibility(ROWS[i], View.GONE)

            if (d != null) {
                v.setTextViewText(R.id.head, (if (d.demo) "DEMO" else d.window.kind.title.uppercase()) + " · " + Fmt.asOf(d))
                val rows = listOfNotNull(d.primary) + d.others
                for ((i, r) in rows.take(ROWS.size).withIndex()) {
                    v.setViewVisibility(ROWS[i], View.VISIBLE)
                    v.setTextViewText(LABELS[i], r.lineLabel)
                    v.setTextViewText(TIMES[i], Ui.times(r, Notifier.color(r)))
                    v.setTextColor(TIMES[i], if (r.hasRealtime) Ui.WHITE else Ui.DIM)
                    v.setTextViewText(SUBS[i], listOf(Fmt.countdown(r, d.now), r.conn.stopName).filter { it.isNotEmpty() }.joinToString(" · "))
                    v.setTextViewText(STATUS[i], r.statusText)
                    v.setTextColor(STATUS[i], Notifier.color(r))
                }
                return v
            }

            val now = Live.clock()
            val store = Store(context)
            val s = store.settings
            val next = Schedule.upcoming(now, s).firstOrNull { it.start.isAfter(now) && it.key != store.dismissedKey }
            if (next == null) {
                v.setTextViewText(R.id.head, if (s.active) "ABFAHRT · KEINE FAHRT GEPLANT" else "ABFAHRT · AUSGESCHALTET")
                return v
            }
            v.setTextViewText(
                R.id.head,
                "${next.kind.title.uppercase()} · ANZEIGE ${dayLabel(next, now.toLocalDate()).uppercase()} AB ${Fmt.time(next.start)}",
            )
            for ((i, c) in next.connections.take(ROWS.size).withIndex()) {
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(LABELS[i], c.label.ifEmpty { "Fahrt" })
                v.setTextViewText(TIMES[i], Fmt.time(c.time))
                v.setTextColor(TIMES[i], Ui.DIM)
                v.setTextViewText(SUBS[i], c.stopName)
                v.setTextViewText(STATUS[i], "Fahrplan")
                v.setTextColor(STATUS[i], Notifier.GREY)
            }
            return v
        }
    }
}
