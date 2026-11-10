package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.names
import com.aditya.clashtimer.core.SampleExports.times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row must never show a countdown at or past its finish (on the phone the chronometer kept
 * running into negative numbers). Uses the Cake-A-Pult module from export_2.json, which
 * finishes at T0 + 74607 with the Apprentice.
 */
class FinishRedrawTest {

    private val state: AppState = (SyncLogic.apply(AppState(), SampleExports.text("export_2.json"))
        as SyncLogic.Outcome.Synced).state
    private val settings = AppSettings()
    private val t0 = export2.timestamp
    private val finish = t0 + 74607
    private val moduleKey = export2.jobs.single { it.dataId == 102000040 }.key

    private fun build(now: Long) = WidgetModelBuilder.build(state, settings, names, times, now)
    private fun module(m: WidgetModel) = m.builderRows.single { it.jobKey == moduleKey }

    private fun assertCompleted(now: Long) {
        val m = build(now)
        val row = module(m)
        assertEquals("at finish ${now - finish}", RowKind.COMPLETED, row.kind)
        assertEquals(WidgetModelBuilder.COMPLETED, row.staticStatus)
        assertEquals(0L, row.remaining)
        assertEquals(1f, row.progress)
        assertEquals(RowButton.NONE, row.button)
        assertEquals("BUILDERS 5/6", m.buildersLabel)
        assertEquals(FooterPart("Apprentice idle", FooterTone.IDLE), m.footerLeft.first())
        // The redraw that posts the notification sees the same job as finished.
        assertTrue(FinishNotices.check(state, settings, names, now).notices.any { it.jobKey == moduleKey })
    }

    @Test fun redrawOneSecondBeforeFinishShowsCompleted() = assertCompleted(finish - 1)

    @Test fun redrawExactlyAtFinishShowsCompleted() = assertCompleted(finish)

    @Test fun redrawOneSecondAfterFinishShowsCompleted() = assertCompleted(finish + 1)

    @Test fun threeSecondsBeforeFinishStillCountsDown() {
        val m = build(finish - 3)
        val row = module(m)
        assertEquals(RowKind.RUNNING, row.kind)
        assertEquals(3L, row.remaining)
        assertEquals("BUILDERS 6/6", m.buildersLabel)
        assertFalse(FinishNotices.check(state, settings, names, finish - 3).notices.any { it.jobKey == moduleKey })
    }

    @Test fun graceIsTwoSeconds() {
        assertFalse(Finish.isDone(100, 97))
        assertTrue(Finish.isDone(100, 98))
        assertTrue(Finish.isDone(100, 100))
        assertTrue(Finish.isDone(100, 101))
    }

    /** Before the flip, the next redraw is the moment the row turns Completed, not the finish itself. */
    @Test fun redrawScheduledWhenRowTurnsCompleted() {
        val now = finish - 10
        val m = build(now)
        assertEquals(finish - Finish.GRACE_SEC, m.nextChangeAt)
        assertEquals((finish - Finish.GRACE_SEC) * 1000, WidgetSchedule.nextUpdateMs(m, now * 1000 + 250, true))
        // Static text mode may redraw earlier for another row's minute text, but never later than the flip.
        assertTrue(WidgetSchedule.nextUpdateMs(m, now * 1000 + 250, false)!! <= (finish - Finish.GRACE_SEC) * 1000)
    }

    /** After the flip, one more redraw lands at finish + 2 s as a safety net. */
    @Test fun safetyRedrawAfterFinish() {
        for (now in listOf(finish - 1, finish, finish + 1)) {
            assertEquals(finish + Finish.GRACE_SEC, build(now).nextChangeAt)
        }
    }

    /** The wakeup finish alarm (which notifies and redraws) fires 2 s after the finish. */
    @Test fun finishAlarmAtFinishPlusTwoSeconds() {
        assertEquals(finish, FinishNotices.nextFinish(state, settings, finish - 10))
        assertEquals(finish + Finish.GRACE_SEC, FinishNotices.alarmAt(state, settings, finish - 10))
        // Once the module counts as done the alarm moves on to the next job.
        assertTrue(FinishNotices.alarmAt(state, settings, finish - 1)!! > finish + Finish.GRACE_SEC)
    }

    /** Sweep: at every render moment around every finish, a running row has more than the grace left. */
    @Test fun noRunningRowAtOrPastItsFinish() {
        val engine = TimerEngine(settings.boosts)
        val finishes = export2.jobs.map { engine.finishTime(it, export2, state.links, state.boosts) }
        for (f in finishes) for (now in f - 5..f + 5) {
            for (row in build(now).allRows.filter { it.kind == RowKind.RUNNING }) {
                assertTrue("${row.label} at ${now - f}", row.finishAt!! - now > Finish.GRACE_SEC)
            }
        }
    }
}
