package com.aditya.clashtimer.crash

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aditya.clashtimer.R
import com.aditya.clashtimer.notify.Notifier
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Uncaught-exception handler for the main process (players have no adb/logcat).
 *
 * On a crash it writes the report to `files/crash/last_crash.txt` (a plain file, not DataStore:
 * the crash screen runs in its own `:crash` process and DataStore is single-process), then opens
 * [CrashActivity]. Android 10+ blocks activity starts from the background (a crash in the widget
 * receiver or an alarm), so when no activity of the app is on screen it also posts a notification
 * that opens the crash screen. The main process is then killed, which replaces the system's
 * "keeps stopping" dialog with the crash screen.
 *
 * If anything here fails, the previous (system) handler runs as before.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val CHANNEL_ID = "crash"
    private const val NOTIFICATION_ID = 9_001
    /** Manifest meta-data set from app/build.gradle.kts: "on" for a minified release, else "off". */
    private const val META_R8 = "com.aditya.clashtimer.R8"

    @Volatile private var startedActivities = 0
    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { if (startedActivities > 0) startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val handled = try {
                handle(app, thread, error)
                true
            } catch (_: Throwable) {
                false
            }
            if (!handled && previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun handle(app: Application, thread: Thread, error: Throwable) {
        save(app, CrashReport.build(info(app, thread.name), error))
        val inForeground = startedActivities > 0
        // Allowed while an activity is visible; silently blocked from the background on Android 10+.
        runCatching { app.startActivity(CrashActivity.intent(app)) }
        if (!inForeground) runCatching { notify(app) }
    }

    fun info(context: Context, thread: String): CrashReport.Info {
        val (name, code) = runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode
            else @Suppress("DEPRECATION") p.versionCode.toLong()
            (p.versionName ?: "?") to c
        }.getOrDefault("?" to -1L)
        return CrashReport.Info(
            versionName = name,
            versionCode = code,
            r8 = r8(context),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            process = processName(context),
            thread = thread,
            epochMs = System.currentTimeMillis(),
        )
    }

    /** "on" / "off" from the manifest, "" if missing. */
    fun r8(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(META_R8)
    }.getOrNull().orEmpty()

    // ---- The saved report (read by the crash screen and the app's diagnostics) ----

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun save(context: Context, text: String) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    fun lastReport(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        cancelNotification(context)
    }

    // ---- Notification for crashes while the app is in the background ----

    private fun notify(context: Context) {
        if (!Notifier.allowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App errors", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when Clash Timers stops because of an error"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, CrashActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Clash Timers stopped")
            .setContentText("Tap to see the error and copy it")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    // ---- Process name (the crash screen's process must not install this handler) ----
    //
    // These run from ClashApp.attachBaseContext, before anything else in the app. They use only
    // plain Java/Android calls (no Kotlin sequences, collection helpers, regex or runCatching),
    // so the crash catcher's own start-up has as little as possible that can fail. 1.0.1 crashed
    // right here on Android 10 because R8 had rewritten Kotlin's String.endsWith.

    fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val name = Application.getProcessName()
            if (name != null && name.length > 0) return name
        }
        var input: FileInputStream? = null
        try {
            input = FileInputStream("/proc/self/cmdline")
            val buf = ByteArray(256)
            val n = input.read(buf)
            var len = 0
            while (len < n && buf[len].toInt() != 0) len++
            if (len > 0) return String(buf, 0, len, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        } finally {
            try {
                input?.close()
            } catch (_: Throwable) {
            }
        }
        return context.packageName
    }

    /** The crash screen runs in `android:process=":crash"` (AndroidManifest.xml). */
    fun isCrashProcess(context: Context): Boolean =
        processName(context) == context.packageName + ":crash"
}
