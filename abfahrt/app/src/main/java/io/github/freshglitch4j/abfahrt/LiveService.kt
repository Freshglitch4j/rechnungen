package io.github.freshglitch4j.abfahrt

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager

/**
 * Vordergrunddienst, der während eines Anzeigefensters läuft. Er hält die
 * Benachrichtigung aktuell; geweckt wird er über exakte Wecker, sodass er
 * zwischen zwei Schritten keinen Strom braucht.
 */
class LiveService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    /**
     * Wird das Display während eines Anzeigefensters ausgeschaltet (Handy gesperrt
     * und weggelegt), öffnet sich die Standanzeige über dem Sperrbildschirm.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) handler.postDelayed({ showStandIfWanted("Display aus") }, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("abfahrt-live").apply { start() }
        handler = Handler(thread.looper)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOff) } catch (e: Exception) { }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun showStandIfWanted(reason: String) {
        val store = Store(this)
        val d = Live.display ?: return
        if (!store.settings.autoStand || StandActivity.alive) return
        if (store.standClosedKey == d.window.key) return
        Notifier.launchStand(this, reason)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ohne Intent wurde der Dienst vom System neu gestartet → wie ein Tick behandeln.
        val action = intent?.action ?: Controller.ACTION_TICK
        try {
            startForeground(
                Notifier.ID_LIVE,
                Notifier.build(this, Live.display),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            Store(this).log("Vordergrunddienst nicht startbar: ${e.javaClass.simpleName} ${e.message}")
        }
        handler.post { work(action) }
        return START_STICKY
    }

    private fun work(action: String) {
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abfahrt:abruf")
        wakeLock.acquire(60_000)
        try {
            val step = try {
                Controller.step(this, action)
            } catch (e: Exception) {
                Store(this).log("Fehler im Arbeitsschritt: $e")
                // Nicht aufgeben: in einer Minute erneut versuchen.
                Scheduler.scheduleTick(this, Live.clock().plusMinutes(1))
                return
            }
            val display = step.display
            if (display == null || step.nextWake == null) {
                Scheduler.cancelTick(this)
                Scheduler.scheduleNextStart(this)
                DepartureWidget.refresh(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(Notifier.ID_LIVE, Notifier.build(this, display))
            DepartureWidget.refresh(this)
            Scheduler.scheduleTick(this, step.nextWake)
            Scheduler.scheduleNextStart(this)
            if (step.windowStarted) showStandIfWanted("Beginn")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        fun start(context: Context, action: String) {
            val intent = Intent(context, LiveService::class.java).setAction(action)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Start aus dem Hintergrund nicht erlaubt: über einen exakten Wecker nachholen.
                Store(context).log("Dienststart verschoben: ${e.javaClass.simpleName}")
                Scheduler.scheduleTick(context, Live.clock().plusSeconds(2))
            }
        }
    }
}
