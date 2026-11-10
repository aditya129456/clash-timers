package com.aditya.clashtimer.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.aditya.clashtimer.core.FinishNotices
import com.aditya.clashtimer.core.WidgetActions
import com.aditya.clashtimer.core.WidgetModelBuilder
import com.aditya.clashtimer.core.WidgetSchedule
import com.aditya.clashtimer.data.Names
import com.aditya.clashtimer.data.StateRepository
import com.aditya.clashtimer.data.Times
import com.aditya.clashtimer.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home-screen widget, drawn with plain RemoteViews (not Glance) so it can use the Lilita One
 * font, Chronometer countdowns, progress bars and the design's drawables.
 * The class name is unchanged from the Glance version so a placed widget keeps working.
 *
 * Also the app's single broadcast entry point: widget button taps (step 5), the redraw and
 * finish alarms, and boot / app update / clock change, which all end in [WidgetUpdater.updateAll].
 */
class ClashWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, "widget")

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context, "widget")

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK -> refresh(context, "widget")
            ACTION_FINISH -> refresh(context, "alarm")
            ACTION_TAP -> tap(context, intent)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, ACTION_QUICKBOOT,
            -> refresh(context, "system")
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) = WidgetAlarm.cancelRedraw(context)

    private fun refresh(context: Context, source: String) = runAsync { WidgetUpdater.updateAll(context, source) }

    /** A widget button: `clashtimer://tap/<action>/<arg>`. Saved, logged, then redrawn at once. */
    private fun tap(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments.orEmpty()
        val action = segments.getOrNull(0) ?: return
        val arg = segments.getOrNull(1) ?: return
        runAsync {
            val app = context.applicationContext
            val repo = StateRepository(app)
            try {
                val settings = repo.currentSettings()
                val now = System.currentTimeMillis() / 1000
                val line = repo.updateState { before ->
                    val after = WidgetActions.apply(before, action, arg, now, settings.boosts)
                    after to WidgetActions.describe(before, after, action, arg, Names.get(app))
                }
                repo.log("tap", null, line)
            } catch (t: Throwable) {
                repo.log("tap", false, "Tap failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            WidgetUpdater.updateAll(app, "tap")
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.aditya.clashtimer.WIDGET_TICK"
        const val ACTION_FINISH = "com.aditya.clashtimer.JOB_FINISH"
        const val ACTION_TAP = "com.aditya.clashtimer.WIDGET_TAP"
        /** Some ROMs send this instead of BOOT_COMPLETED after a fast reboot. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

object WidgetUpdater {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ClashWidgetReceiver::class.java))

    /**
     * Everything that follows a change or an alarm: posts "… free" notifications for jobs that just
     * finished, re-renders every placed widget, and schedules the redraw alarm and the finish alarm.
     * Returns the number of widgets, or -1 on failure (logged to the on-screen sync log; never throws).
     */
    suspend fun updateAll(context: Context, source: String): Int {
        val app = context.applicationContext
        val repo = StateRepository(app)
        return try {
            val settings = repo.currentSettings()
            val names = Names.get(app)
            val nowMs = System.currentTimeMillis()
            val now = nowMs / 1000

            // Notifications first, marked as sent in the same atomic update so two alarms can't double-post.
            // Own try: a notification failure must never skip the redraw below, which turns the
            // finished rows into "Completed ✓" in the same pass that announced them.
            try {
                val notices = repo.updateState { state ->
                    val r = FinishNotices.check(state, settings, names, now)
                    val fresh = r.notices.filter { it.jobKey !in state.notified }
                    state.copy(notified = r.notified) to fresh
                }
                if (notices.isNotEmpty()) {
                    if (settings.notificationsEnabled) {
                        val posted = Notifier.post(app, notices)
                        repo.log(source, null, if (posted) "Notified: " + notices.joinToString("; ") { it.title }
                            else "Not notified (notifications are blocked for the app): " + notices.joinToString("; ") { it.title })
                    } else {
                        repo.log(source, null, "Finished (notifications off): " + notices.joinToString("; ") { it.title })
                    }
                }
            } catch (t: Throwable) {
                repo.log(source, false, "Notification failed: ${t.javaClass.simpleName}: ${t.message}")
            }

            val state = repo.currentState()
            // Finish + GRACE_SEC, so the redraw it triggers sees the job as done.
            WidgetAlarm.scheduleFinish(app, FinishNotices.alarmAt(state, settings, now)?.times(1000))

            val manager = AppWidgetManager.getInstance(app)
            val ids = widgetIds(app)
            if (ids.isEmpty()) {
                WidgetAlarm.cancelRedraw(app)
                return 0
            }
            val model = WidgetModelBuilder.build(state, settings, names, Times.get(app), now)
            val renderer = WidgetRenderer(app)
            for (id in ids) {
                manager.updateAppWidget(id, renderer.render(model, settings.liveCountdown, manager.getAppWidgetOptions(id)))
            }
            WidgetAlarm.scheduleRedraw(app, WidgetSchedule.nextUpdateMs(model, nowMs, settings.liveCountdown))
            ids.size
        } catch (t: Throwable) {
            repo.log(source, false, "Widget refresh failed: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }
}

/**
 * Two pending alarms:
 * - **redraw** (non-wakeup `RTC`): countdown edges, boost edges, footer text. If the phone is asleep
 *   it is delivered when the screen comes back on, which is when the widget is visible anyway.
 * - **finish** (wakeup, allowed while idle): the next time any job finishes, so the notification
 *   arrives on time even with the screen off.
 */
object WidgetAlarm {
    /** Fire slightly after an edge so the re-render lands in the next countdown segment. */
    private const val SLACK_MS = 250L
    private const val MIN_DELAY_MS = 1_000L

    private fun intent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ClashWidgetReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun scheduleRedraw(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, ClashWidgetReceiver.ACTION_TICK, 1)
        if (atMs == null) return am.cancel(pi)
        val at = maxOf(atMs + SLACK_MS, System.currentTimeMillis() + MIN_DELAY_MS)
        try {
            if (canScheduleExact(context)) am.setExact(AlarmManager.RTC, at, pi)
            else am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
        }
    }

    fun scheduleFinish(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, ClashWidgetReceiver.ACTION_FINISH, 2)
        if (atMs == null) return am.cancel(pi)
        val at = maxOf(atMs + SLACK_MS, System.currentTimeMillis() + MIN_DELAY_MS)
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelRedraw(context: Context) = scheduleRedraw(context, null)
}
