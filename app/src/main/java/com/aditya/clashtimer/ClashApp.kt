package com.aditya.clashtimer

import android.app.Application
import android.content.Context
import com.aditya.clashtimer.crash.CrashReporter

/**
 * Installs the crash catcher as early as possible: `attachBaseContext` runs before any
 * ContentProvider (androidx.startup) and before `onCreate`, so even start-up crashes are caught.
 * The `:crash` process (the crash screen itself) keeps the system handler, so a broken crash
 * screen can never loop.
 */
class ClashApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // The crash catcher must never be the thing that stops the app from starting.
        try {
            if (!CrashReporter.isCrashProcess(this)) CrashReporter.install(this)
        } catch (_: Throwable) {
        }
    }
}
