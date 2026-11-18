package io.github.freshglitch4j.abfahrt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import io.github.freshglitch4j.abfahrt.core.Display
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Row
import io.github.freshglitch4j.abfahrt.core.Status
import io.github.freshglitch4j.abfahrt.core.ZONE

/** Baut die laufende Benachrichtigung – die eigentliche Anzeige auf dem Sperrbildschirm. */
object Notifier {
    const val ID_LIVE = 1
    const val ID_STAND = 2
    const val CHANNEL_LIVE = "live"
    const val CHANNEL_STAND = "stand"

    /** Seit Android 16: Bitte um Darstellung als Live-Benachrichtigung (Now Bar, Statusleisten-Kapsel). */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    const val GREEN = 0xFF3DDC84.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val RED = 0xFFFF5252.toInt()
    const val GREY = 0xFF9E9E9E.toInt()

    const val YELLOW = 0xFFFFD600.toInt()
    const val PURPLE = 0xFFC060FF.toInt()

    /** Bis zu so vielen Minuten Verspätung gelb, darüber rot. */
    const val YELLOW_MAX_DELAY = 3

    /** Farbe nach Verspätung: pünktlich/früher grün, bis 3 Min gelb, mehr rot, Ausfall lila, ohne Echtzeit grau. */
    fun color(r: Row): Int = when {
        r.status == Status.CANCELLED -> PURPLE
        r.expected == null || r.delayMin == null -> GREY
        r.delayMin <= 0 -> GREEN
        r.delayMin <= YELLOW_MAX_DELAY -> YELLOW
        else -> RED
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "Abfahrten (Echtzeit)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Laufende Anzeige der nächsten Abfahrten – ohne Ton"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STAND, "Standanzeige einschalten", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Schaltet die Standanzeige zu Beginn automatisch ein (nur wenn in der App aktiviert)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun service(context: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context, rc, Intent(context, LiveService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun standIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 10,
            Intent(context, StandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Kurzform einer weiteren Fahrt, z. B. „REX 2 16:59 pünktlich“. */
    private fun compact(r: Row): String = "${r.lineLabel} ${Fmt.time(r.effective)} ${r.statusText}"

    private fun detail(r: Row, d: Display): String {
        val parts = listOf(Fmt.countdown(r, d.now), Fmt.where(r)).filter { it.isNotEmpty() }
        return parts.joinToString(" · ") + if (r.note.isNotEmpty()) "\n${r.note}" else ""
    }

    /**
     * Baut die Benachrichtigung so, dass das System sie als Live-Benachrichtigung
     * annimmt. Android 16.0 verlangte dafür „colorized“, neuere Stände (ab
     * 16 QPR1, also auch One UI 8.5) verlangen das Gegenteil – daher wird die
     * Variante gewählt, die das laufende System als geeignet einstuft.
     */
    fun build(context: Context, d: Display?): Notification {
        // Samsung stellt Live-Benachrichtigungen fremder Apps nicht in jedem Fall in die
        // Now Bar und zeigt sie dann am Sperrbildschirm nur als Symbol. Daher standardmäßig
        // eine normale laufende Benachrichtigung, die als volle Karte erscheint.
        if (!Store(context).settings.nowBar) return build(context, d, colorized = false, promote = false)
        val plain = build(context, d, colorized = false)
        if (Build.VERSION.SDK_INT < 36 || plain.hasPromotableCharacteristics()) return plain
        val colorized = build(context, d, colorized = true)
        return if (colorized.hasPromotableCharacteristics()) colorized else plain
    }

    /** Wird die laufende Benachrichtigung vom System gerade als Live-Benachrichtigung geführt? null = keine aktiv. */
    fun isPromotedNow(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == ID_LIVE } ?: return null
        return active.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
    }

    fun build(context: Context, d: Display?, colorized: Boolean, promote: Boolean = true): Notification {
        val b = Notification.Builder(context, CHANNEL_LIVE)
            .setColorized(colorized)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(standIntent(context))
        if (promote) b.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

        val p = d?.primary
        if (d == null || p == null) {
            return b.setContentTitle("Abfahrt").setContentText("Wird aktualisiert …").setShowWhen(false).build()
        }
        val others = d.others

        val title = Fmt.line(p)
        val text = (listOf(Fmt.countdown(p, d.now)) + if (others.isEmpty()) listOf(Fmt.where(p)) else others.map(::compact))
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val big = buildString {
            append(detail(p, d))
            for (r in others) {
                append("\n\n").append(Fmt.line(r)).append('\n').append(detail(r, d))
            }
        }
        val sub = (if (d.demo) "DEMO – erfundene Werte" else d.window.kind.title) + " · " + Fmt.asOf(d)

        b.setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setColor(color(p))
            // Sekundengenauer Countdown im Kopf der Benachrichtigung – läuft ohne Zutun der App.
            .setWhen(p.effective.atZone(ZONE).toInstant().toEpochMilli())
            .setShowWhen(p.status != Status.CANCELLED)
            .setUsesChronometer(p.status != Status.CANCELLED)
            .setChronometerCountDown(true)
            .setDeleteIntent(service(context, Controller.ACTION_DISMISS, 23))
        if (Build.VERSION.SDK_INT >= 36) b.setShortCriticalText(Fmt.chip(p))
        // Android 17: Kennzahlen-Vorlage – laut Samsung erscheinen solche Live-Benachrichtigungen
        // in der Now Bar (Sperrbildschirm und Always-On-Display).
        if (promote && Build.VERSION.SDK_INT >= 37) b.setStyle(metricStyle(p, others.firstOrNull { !it.gone }))

        val icon = Icon.createWithResource(context, R.drawable.ic_stat_bus)
        b.addAction(Notification.Action.Builder(icon, "Aktualisieren", service(context, Controller.ACTION_REFRESH, 20)).build())
        if (d.rows.count { !it.gone } > 1) {
            val next = d.rows.filter { !it.gone }.let { open -> open[(open.indexOf(p) + 1) % open.size] }
            b.addAction(Notification.Action.Builder(icon, "Vorn: ${next.lineLabel} ${Fmt.time(next.planned)}", service(context, Controller.ACTION_SWITCH, 21)).build())
        }
        b.addAction(Notification.Action.Builder(icon, if (d.demo) "Demo beenden" else "Heute ausblenden",
            service(context, if (d.demo) Controller.ACTION_DEMO_STOP else Controller.ACTION_DISMISS, 22)).build())
        return b.build()
    }

    /**
     * Sperrbildschirm-Test: drei Varianten nebeneinander, um herauszufinden, welche
     * Eigenschaft das Gerät dazu bringt, nur ein Symbol statt der Karte zu zeigen.
     * Verschwinden nach drei Minuten von selbst.
     */
    fun postLockscreenTest(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun post(id: Int, title: String, ongoing: Boolean, category: String?) {
            val b = Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(title)
                .setContentText("16:46 → 16:51  +5 Min · in 11 Min")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(180_000)
            if (category != null) b.setCategory(category)
            nm.notify(id, b.build())
        }
        post(ID_TEST_A, "Test A: normal", ongoing = false, category = null)
        post(ID_TEST_B, "Test B: laufend", ongoing = true, category = null)
        post(ID_TEST_C, "Test C: laufend + Status", ongoing = true, category = Notification.CATEGORY_STATUS)
    }

    const val ID_TEST_A = 91
    const val ID_TEST_B = 92
    const val ID_TEST_C = 93

    /**
     * Kennzahlen für die Now Bar: erwartete Abfahrt, Status bzw. Verspätung und die
     * zweite Fahrt (oder die verbleibende Zeit). Ohne Echtzeit steht „Plan" im Etikett.
     */
    @androidx.annotation.RequiresApi(37)
    private fun metricStyle(p: Row, second: Row?): Notification.MetricStyle {
        fun timeOf(r: Row) = Notification.Metric(
            Notification.Metric.FixedTime(r.effective.toLocalTime()),
            r.lineLabel + if (r.hasRealtime) "" else " (Plan)",
        )
        val style = Notification.MetricStyle()
            .addMetric(timeOf(p))
            .addMetric(Notification.Metric(Notification.Metric.FixedText(p.statusText), "Status"))
        if (second != null) {
            style.addMetric(timeOf(second))
        } else if (p.status != Status.CANCELLED) {
            style.addMetric(Notification.Metric(
                Notification.Metric.TimeDifference.forTimer(p.effective.atZone(ZONE).toInstant(), Notification.Metric.TimeDifference.FORMAT_ADAPTIVE),
                "Abfahrt in",
            ))
        }
        return style.setCriticalMetric(0)
    }

    /** Schaltet die Standanzeige über dem Sperrbildschirm ein (Vollbild-Intent). */
    fun launchStand(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent()) {
            Store(context).log("Standanzeige: Vollbild-Berechtigung fehlt")
            return
        }
        val n = Notification.Builder(context, CHANNEL_STAND)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle("Abfahrten")
            .setContentText("Standanzeige öffnen")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(standIntent(context))
            .setFullScreenIntent(standIntent(context), true)
            .build()
        nm.notify(ID_STAND, n)
    }
}
