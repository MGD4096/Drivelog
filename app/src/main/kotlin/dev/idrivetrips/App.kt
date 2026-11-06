package dev.idrivetrips

import android.app.Application

/** Runs before anything else in the app (also when the car's Bluetooth starts it in the background): sets the language. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyLanguage(this)
        Colors.init(this)
    }
}
