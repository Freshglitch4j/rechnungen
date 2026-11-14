package io.github.freshglitch4j.abfahrt

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // Jeder Prozessstart stellt die Wecker sicher – so heilt sich die Zeitsteuerung selbst.
        try {
            Scheduler.ensure(this)
        } catch (e: Exception) {
            Store(this).log("Einplanen beim Start fehlgeschlagen: $e")
        }
    }
}
