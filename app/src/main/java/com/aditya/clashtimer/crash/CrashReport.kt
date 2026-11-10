package com.aditya.clashtimer.crash

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The text of a crash report: a short header (version, R8 on/off, phone, process, thread, time)
 * and the full stack trace with every "Caused by". Pure Kotlin so it is unit-tested.
 *
 * The app's own classes are never renamed by R8 (`packageScope` in app/build.gradle.kts), so
 * `com.aditya.clashtimer` frames are readable as-is. Library frames (androidx/kotlin/kotlinx) are
 * renamed in a minified build and need that build's R8 mapping to decode. Release builds are
 * not minified, so current reports are readable as they are.
 */
object CrashReport {
    const val TITLE = "Clash Timers crash report"
    /** Keeps the clipboard / file small even for a StackOverflowError. */
    const val MAX_CHARS = 60_000

    data class Info(
        val versionName: String,
        val versionCode: Long,
        /** "on" (minified release), "off" (nominify or debug), or "" if unknown. */
        val r8: String,
        val androidRelease: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val process: String,
        val thread: String,
        val epochMs: Long,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")

    fun build(info: Info, error: Throwable): String {
        val header = buildString {
            appendLine(TITLE)
            appendLine("App: ${info.versionName} (${info.versionCode}), R8 minify: ${info.r8.ifEmpty { "unknown" }}")
            appendLine("Phone: ${info.manufacturer} ${info.model}, Android ${info.androidRelease} (API ${info.sdkInt})")
            appendLine("Process: ${info.process}, thread: ${info.thread}")
            appendLine("Time: ${TIME.format(Instant.ofEpochMilli(info.epochMs).atZone(info.zone))}")
            if (info.r8 == "on") appendLine("Retrace: tools/retrace.sh ${info.versionName} <this file>")
            appendLine()
        }
        val trace = stackTrace(error)
        val text = header + trace
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n… (cut at $MAX_CHARS characters)"
    }

    fun stackTrace(error: Throwable): String {
        val out = StringWriter()
        PrintWriter(out).use { error.printStackTrace(it) }
        return out.toString().trimEnd() + "\n"
    }

    /** "IllegalStateException: Module with the Main dispatcher is missing", for the diagnostics line. */
    fun summary(report: String): String {
        val first = report.lineSequence()
            .dropWhile { it.isNotBlank() } // skip the header block
            .firstOrNull { it.isNotBlank() }
            ?: return ""
        // Drop the package from the class name only; the message may contain dots too.
        val colon = first.indexOf(':').let { if (it < 0) first.length else it }
        return first.substring(0, colon).substringAfterLast('.') + first.substring(colon)
    }

    /** The "Time:" line of a saved report, or null. */
    fun time(report: String): String? =
        report.lineSequence().firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ")
}
