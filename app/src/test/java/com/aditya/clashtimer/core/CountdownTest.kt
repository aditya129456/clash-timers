package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.Countdown.DAY_MS
import com.aditya.clashtimer.core.Countdown.HOUR_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownTest {

    @Test fun textFormat() {
        assertEquals("10:57:12", Countdown.text(10 * 3600 + 57 * 60 + 12))
        assertEquals("1d 00:02:31", Countdown.text(86400 + 151))
        assertEquals("0:41:16", Countdown.text(41 * 60 + 16))
        assertEquals("1:12:40", Countdown.text(3600 + 12 * 60 + 40))
        assertEquals("6d 13:25:23", Countdown.text(566723))
        assertEquals("0:00:00", Countdown.text(-3))
    }

    @Test fun segments() {
        val finish = 10_000_000L // epoch s
        fun at(leftMs: Long) = Countdown.segment(finish, finish * 1000 - leftMs)!!

        at(DAY_MS + 10 * HOUR_MS + 500).let {
            assertEquals("1d %s", it.format)
            assertEquals(10 * HOUR_MS + 500, it.chronometerMs)
            assertEquals(finish * 1000 - DAY_MS - 10 * HOUR_MS, it.nextEdgeMs)
        }
        at(DAY_MS + 10 * HOUR_MS - 1).let {
            assertEquals("1d 0%s", it.format)
            assertEquals(finish * 1000 - DAY_MS - HOUR_MS, it.nextEdgeMs)
        }
        at(DAY_MS + 30 * 60_000).let {
            assertEquals("1d 00:%s", it.format)
            assertEquals(finish * 1000 - DAY_MS, it.nextEdgeMs) // day rollover
        }
        at(5 * HOUR_MS).let {
            assertEquals("%s", it.format)
            assertEquals(finish * 1000 - HOUR_MS, it.nextEdgeMs)
        }
        at(30 * 60_000).let {
            assertEquals("0:%s", it.format)
            assertEquals(finish * 1000, it.nextEdgeMs) // the finish itself
        }
        assertNull(Countdown.segment(finish, finish * 1000))
        assertNull(Countdown.segment(finish, finish * 1000 + 5))
    }

    /** What Android's Chronometer shows in countdown mode: `H:MM:SS`, or `MM:SS` under an hour. */
    private fun chronometer(format: String, baseMinusNowMs: Long): String {
        val s = baseMinusNowMs / 1000 // Chronometer truncates
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val elapsed = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
        return format.replace("%s", elapsed)
    }

    /**
     * Renders at many moments over 12 days and checks that the chronometer, ticking from the render
     * until the segment's edge, always shows exactly [Countdown.text] of the real time left.
     */
    @Test fun chronometerMatchesTextUntilEachEdge() {
        val finish = 1_800_000_000L
        val finishMs = finish * 1000
        var leftMs = 12 * DAY_MS + 123_457
        var checked = 0
        while (leftMs > 0) {
            // Renders land 250 ms after an edge (WidgetAlarm), never exactly on one.
            if (leftMs % HOUR_MS == 0L) leftMs -= 250
            val renderAt = finishMs - leftMs
            val seg = Countdown.segment(finish, renderAt)!!
            assertTrue(seg.nextEdgeMs > renderAt)
            // Sample the segment: just after rendering, in the middle and 1 ms before its edge.
            for (t in listOf(renderAt, (renderAt + seg.nextEdgeMs) / 2, seg.nextEdgeMs - 1)) {
                val shown = chronometer(seg.format, seg.chronometerMs - (t - renderAt))
                assertEquals("left ${finishMs - t} ms", Countdown.text((finishMs - t) / 1000), shown)
                checked++
            }
            leftMs -= 7 * 60_000 + 13_001 // walk in odd steps so every segment kind gets hit
        }
        assertTrue(checked > 3000)
    }

    @Test fun staticTextEdges() {
        val finish = 100_000L
        // 1h 2m (62 minutes, rounded up) → 1h 1m when 61 minutes are left.
        assertEquals(finish - 61 * 60, WidgetSchedule.staticTextEdge(finish, finish - 3700))
        // 1d 3h → 1d 2h when 1619 minutes (26 h 59 m) are left.
        assertEquals(finish - 1619 * 60, WidgetSchedule.staticTextEdge(finish, finish - (86400 + 3 * 3600 + 100)))
        assertEquals(finish, WidgetSchedule.staticTextEdge(finish, finish - 30)) // 1m → done
        assertNull(WidgetSchedule.staticTextEdge(finish, finish))
        // Sanity: the text really changes at each edge and not before.
        var now = finish - 3 * 86400
        while (true) {
            val edge = WidgetSchedule.staticTextEdge(finish, now) ?: break
            val text = WidgetModelBuilder.formatRemaining(finish - now)
            assertEquals(text, WidgetModelBuilder.formatRemaining(finish - (edge - 1)))
            if (edge < finish) assertTrue(text != WidgetModelBuilder.formatRemaining(finish - edge))
            now = edge
        }
        assertEquals(finish, now)
    }
}
