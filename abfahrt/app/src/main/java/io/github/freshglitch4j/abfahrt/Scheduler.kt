package io.github.freshglitch4j.abfahrt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.ZONE
import java.time.LocalDateTime

/**
 * Zeitsteuerung über exakte Wecker. Sie lösen auch im Stromsparmodus (Doze)
 * aus und erlauben es, den Vordergrunddienst aus dem Hintergrund zu starten.
 */
object Scheduler {
    private const val RC_START = 1
    private const val RC_TICK = 2

    private fun pending(context: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, rc,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun setExact(context: Context, at: LocalDateTime, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val millis = at.atZone(ZONE).toInstant().toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            // Exakte Wecker entzogen: ungenauer Wecker ist besser als keiner.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    /** Stellt den Wecker für den Beginn des nächsten Anzeigefensters. */
    fun scheduleNextStart(context: Context, now: LocalDateTime = Live.clock()): LocalDateTime? {
        val store = Store(context)
        val pi = pending(context, AlarmReceiver.ACTION_START, RC_START)
        val next = Schedule.upcoming(now, store.settings)
            .firstOrNull { it.start.isAfter(now) && it.key != store.dismissedKey }
        if (next == null) {
            context.getSystemService(AlarmManager::class.java).cancel(pi)
            return null
        }
        setExact(context, next.start, pi)
        return next.start
    }

    fun scheduleTick(context: Context, at: LocalDateTime) {
        setExact(context, at, pending(context, AlarmReceiver.ACTION_TICK, RC_TICK))
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, AlarmReceiver.ACTION_TICK, RC_TICK))
    }

    /**
     * Bringt alles in den Sollzustand: nächsten Start einplanen und – falls
     * gerade ein Fenster läuft – den Dienst starten. Aufruf nach App-Start,
     * Geräteneustart, Zeitumstellung und jeder Änderung der Einstellungen.
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        scheduleNextStart(app)
        if (Controller.somethingActive(app) || Live.window != null) {
            LiveService.start(app, Controller.ACTION_TICK)
        } else {
            DepartureWidget.refresh(app)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> Scheduler.ensure(context)
            ACTION_TICK -> LiveService.start(context, Controller.ACTION_TICK)
        }
    }

    companion object {
        const val ACTION_START = "io.github.freshglitch4j.abfahrt.ALARM_START"
        const val ACTION_TICK = "io.github.freshglitch4j.abfahrt.ALARM_TICK"
    }
}

/** Nach Neustart, App-Update oder Zeitänderung gehen Wecker verloren bzw. stimmen nicht mehr. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Store(context).log("Neu eingeplant nach ${intent.action?.substringAfterLast('.')}")
                Scheduler.ensure(context)
            }
        }
    }
}
