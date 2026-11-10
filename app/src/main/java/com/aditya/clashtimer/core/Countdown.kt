package com.aditya.clashtimer.core

import java.util.Locale

/**
 * The widget's per-second countdown: `10:57:12`, `0:41:16`, `1d 00:02:31`.
 *
 * The widget draws it with a RemoteViews `Chronometer` in countdown mode. A chronometer only
 * formats `H:MM:SS` (or `MM:SS` under an hour) and knows nothing about days, so the countdown is
 * split into segments. Each segment has a fixed format string around the chronometer's own text
 * and an edge where the format must change: a day rollover, the drop below 10 h (two-digit hours
 * after a day prefix) and the drop below 1 h. The widget is re-rendered at each edge by an exact
 * alarm.
 *
 * The countdown is wall-clock time until the predicted finish, which already includes every
 * planned potion and helper hour, so it ticks at 1 s per second even while a boost is running.
 */
object Countdown {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS

    /**
     * @param format chronometer format; `%s` is replaced by the chronometer's `H:MM:SS`/`MM:SS`.
     * @param chronometerMs what the chronometer counts down from, at render time.
     * @param nextEdgeMs epoch ms when this segment ends and the widget must be re-rendered.
     */
    data class Segment(val format: String, val chronometerMs: Long, val nextEdgeMs: Long)

    /** Segment for a job finishing at [finishAtSec], rendered at [nowMs]; null once finished. */
    fun segment(finishAtSec: Long, nowMs: Long): Segment? {
        val finishMs = finishAtSec * 1000
        val left = finishMs - nowMs
        if (left <= 0) return null
        val days = left / DAY_MS
        val inDay = left - days * DAY_MS
        val dayBase = days * DAY_MS
        val (format, edgeLeft) = when {
            days > 0 && inDay >= 10 * HOUR_MS -> "${days}d %s" to dayBase + 10 * HOUR_MS
            days > 0 && inDay >= HOUR_MS -> "${days}d 0%s" to dayBase + HOUR_MS
            days > 0 -> "${days}d 00:%s" to dayBase
            inDay >= HOUR_MS -> "%s" to HOUR_MS
            else -> "0:%s" to 0L
        }
        return Segment(format, inDay, finishMs - edgeLeft)
    }

    /** The same text as a plain string, e.g. for the launcher-screen preview. */
    fun text(remainingSec: Long): String {
        val s = remainingSec.coerceAtLeast(0)
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (d > 0) String.format(Locale.ROOT, "%dd %02d:%02d:%02d", d, h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    }
}
