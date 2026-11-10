package com.aditya.clashtimer.sync

import android.content.ClipboardManager
import android.content.Context
import com.aditya.clashtimer.core.SyncLogic
import com.aditya.clashtimer.core.describe
import com.aditya.clashtimer.data.Names
import com.aditya.clashtimer.data.StateRepository
import com.aditya.clashtimer.widget.WidgetUpdater
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One sync pipeline for both the widget's Sync button and the launcher screen's button. */
object SyncRunner {

    data class Result(val ok: Boolean, val message: String)

    /**
     * Reads the primary clip as text. Must be called while the calling activity has window focus
     * (Android 10+ only lets the focused app read the clipboard). Returns null if empty or blocked.
     */
    fun readClipboard(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Parses [clipboard], saves the new state, logs the result and refreshes the widget.
     * Runs to completion even if the caller's scope is cancelled (the activity may finish).
     * Never throws: errors are logged and returned as a failed [Result].
     */
    suspend fun sync(context: Context, clipboard: String?, source: String): Result = withContext(NonCancellable) {
        val app = context.applicationContext
        val repo = StateRepository(app)
        val result = try {
            val outcome = repo.updateState { old ->
                val o = SyncLogic.apply(old, clipboard)
                o.state to o
            }
            Result(outcome is SyncLogic.Outcome.Synced, plainText(outcome.describe(Names.get(app))))
        } catch (t: Throwable) {
            Result(false, "Error: ${t.javaClass.simpleName}: ${t.message}")
        }
        repo.log(source, result.ok, result.message)
        refreshWidgets(app, source)
        result
    }

    /** Re-renders every placed widget; logs failures and how many widgets are placed. */
    suspend fun refreshWidgets(context: Context, source: String): Int {
        val count = WidgetUpdater.updateAll(context, source)
        if (count == 0) StateRepository(context).log(source, null, "No Clash Timers widget is placed on the home screen")
        return count
    }

    /**
     * Text shown by system UI (toasts, the launcher screen) uses the phone's font, which draws "→"
     * as "ʼn" on a HiOS (Tecno) phone (the APK's strings are correct UTF-8). Use ASCII there.
     */
    fun plainText(s: String): String = s.replace(" → ", " -> ").replace("→", "->")
}
