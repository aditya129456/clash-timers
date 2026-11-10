package com.aditya.clashtimer.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aditya.clashtimer.MainActivity

/**
 * "Clash Timers stopped" screen with the error text and a Copy button. Runs in its own `:crash`
 * process (manifest), uses only framework views (no Compose, no DataStore), and reads the report
 * that [CrashReporter] saved to a file, so it works even when the app's own code is what broke.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.cancelNotification(this)
        val report = CrashReporter.lastReport(this) ?: "No crash report was saved."
        setContentView(content(report))
    }

    private fun content(report: String): View {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Clash Timers stopped"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        column.addView(TextView(this).apply {
            text = "The app hit an error and was closed. Tap Copy and paste the text into your chat, " +
                "so the cause can be found. The same text stays in the app under Diagnostics > Last crash."
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Copy") { copy(report) })
        buttons.addView(button("Open app") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        })
        buttons.addView(button("Close") { finish() })
        column.addView(buttons)

        // Long lines scroll sideways instead of wrapping, so stack frames stay one per line.
        column.addView(HorizontalScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.BLACK)
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, 0)
            })
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column, MATCH_PARENT, WRAP_CONTENT)
            // targetSdk 35+ draws edge to edge: keep the text clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
    }

    private fun copy(report: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Clash Timers crash", report))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, CrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
