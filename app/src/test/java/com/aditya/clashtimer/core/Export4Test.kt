package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export4
import com.aditya.clashtimer.core.SampleExports.names
import com.aditya.clashtimer.core.SampleExports.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `export_4.json`: `helper_timer`, the 23 h helper cycle, tap actions and notifications. */
class Export4Test {

    private val t0 = 1793555062L
    private val text4 = SampleExports.text("export_4.json")
    private val engine = TimerEngine()
    private val synced: AppState = (SyncLogic.apply(AppState(), text4) as SyncLogic.Outcome.Synced).state

    private val inferno = export4.builderJobs.single { it.dataId == 1000027 }
    private val logger = export4.builderJobs.single { it.dataId == 107000008 }
    private val king = export4.builderJobs.single { it.dataId == 28000000 }
    private val goblin = export4.builderJobs.single { it.isGoblin }
    private val lab = export4.labJob!!
    private val pet = export4.petJob!!

    // ---- Parser + engine ----

    @Test fun header() {
        assertEquals(t0, export4.timestamp)
        assertEquals("Sun 01 Nov 17:44:22", utc(t0))
        assertEquals(79479L, export4.helperCooldown)
        assertEquals("Mon 02 Nov 15:49:01", utc(export4.nextHelperActivation!!))
        assertEquals(7515L, export4.builderBoostRemaining)
    }

    @Test fun helperTimerIsParsed() {
        assertEquals(4000023, lab.dataId)
        assertEquals(279L, lab.helperTimer)
        assertTrue(lab.helperLinked)
        assertTrue(export4.jobs.filter { it != lab }.all { it.helperTimer == 0L })
        // The Apprentice on the Logger is not in the export: nothing is linked to a builder job.
        assertTrue(export4.builderJobs.none { it.helperLinked })
        assertFalse(logger.helperLinked)
    }

    @Test fun defaultHelperCycleIs23Hours() {
        assertEquals(82800L, BoostSettings().helperPeriodSec)
        // The running session started 3600 − 279 s before T0; the next one 23 h after that start.
        assertEquals(t0 - (3600 - 279) + 82800, t0 + 79479)
    }

    @Test fun labFinishWithCurrentSessionAndTheCycle() {
        // [T0, T0+279] at 13× → 515010 left. Then 1× until each session (T0 + 79479 + k·82800), 13× for 1 h:
        // 435810 → 389010 → 309810 → 263010 → 183810 → 137010 → 57810 → 11010, then 11010 s at 1×.
        assertEquals(t0 + 331479 + 11010, engine.finishTime(lab, export4, synced.links, synced.boosts))
        // The current session alone is worth 279 × 12 = 3348 s.
        val labNoSession = lab.copy(helperTimer = 0)
        val noCurrent = export4.copy(jobs = export4.jobs.map { if (it == lab) labNoSession else it })
        val withoutCurrent = engine.remainingAt(labNoSession, noCurrent, synced.links, synced.boosts, t0 + 279)
        val withCurrent = engine.remainingAt(lab, export4, synced.links, synced.boosts, t0 + 279)
        assertEquals(3348L, withoutCurrent - withCurrent)
    }

    @Test fun currentSessionOnlyCountsWhileLinked() {
        val unlinked = synced.copy(links = HelperLinks())
        assertEquals(t0 + 518637, engine.finishTime(lab, export4, unlinked.links, unlinked.boosts))
    }

    @Test fun builderBoostFromExport() {
        assertEquals(listOf(BoostUse(BoostType.BUILDER_POTION, t0, t0 + 7515)), synced.boosts)
        // 10× for 7515 s: Inferno (3518) ends at 351.8 s, the Goblin's Wizard Tower at 2918 s, the King at 5083.4 s.
        assertEquals(t0 + 352, engine.finishTime(inferno, export4, synced.links, synced.boosts))
        assertEquals(t0 + 2918, engine.finishTime(goblin, export4, synced.links, synced.boosts))
        assertEquals(t0 + 5084, engine.finishTime(king, export4, synced.links, synced.boosts))
    }

    // ---- Step 5: widget buttons ----

    @Test fun plusLinksMovesAndUnlinksTheApprentice() {
        val onLogger = WidgetActions.toggleHelper(synced, logger.key)
        assertEquals(logger.key, onLogger.links.apprenticeJobKey)
        assertEquals(lab.key, onLogger.links.labAssistantJobKey) // the Lab Assistant is untouched
        assertEquals(
            "Apprentice linked to Logger → 5",
            WidgetActions.describe(synced, onLogger, WidgetActions.HELPER, logger.key, names),
        )

        val moved = WidgetActions.toggleHelper(onLogger, king.key)
        assertEquals(king.key, moved.links.apprenticeJobKey)
        assertEquals(
            "Apprentice linked to Barbarian King → 99 (moved from Logger → 5)",
            WidgetActions.describe(onLogger, moved, WidgetActions.HELPER, king.key, names),
        )

        val off = WidgetActions.toggleHelper(moved, king.key) // tap the gold H
        assertNull(off.links.apprenticeJobKey)
        assertEquals(
            "Apprentice unlinked from Barbarian King → 99",
            WidgetActions.describe(moved, off, WidgetActions.HELPER, king.key, names),
        )
    }

    @Test fun labHelperTapAndPetsHaveNone() {
        val off = WidgetActions.apply(synced, WidgetActions.HELPER, lab.key, t0, BoostSettings())
        assertNull(off.links.labAssistantJobKey)
        assertEquals(lab.key, WidgetActions.toggleHelper(off, lab.key).links.labAssistantJobKey)
        assertEquals(synced, WidgetActions.toggleHelper(synced, pet.key))
        assertEquals(synced, WidgetActions.toggleHelper(synced, "BUILDER:999:1#0"))
        assertEquals(synced, WidgetActions.apply(synced, "nonsense", "x", t0, BoostSettings()))
    }

    @Test fun manualLinkSurvivesResyncWhenTheExportHasNone() {
        val onLogger = WidgetActions.toggleHelper(synced, logger.key)
        val resynced = (SyncLogic.apply(onLogger, text4) as SyncLogic.Outcome.Synced).state
        assertEquals(logger.key, resynced.links.apprenticeJobKey)
    }

    @Test fun apprenticeOnLoggerChangesItsFinish() {
        val onLogger = WidgetActions.toggleHelper(synced, logger.key)
        val before = engine.finishTime(logger, export4, synced.links, synced.boosts)
        val after = engine.finishTime(logger, export4, onLogger.links, onLogger.boosts)
        // Without the Apprentice: 331467 − 7515 × 9 (exported potion) = T0 + 263832. Sessions at T0 + 79479 and
        // T0 + 162279 each save 8 h; the third (T0 + 245079) comes after the new finish (T0 + 206232).
        assertEquals(t0 + 263832, before)
        assertEquals(2 * 8 * 3600L, before - after)
    }

    @Test fun potionsStackAndUndo() {
        val s = BoostSettings()
        val now = t0 + 100
        // A builder potion while the exported one runs is queued right after it.
        val more = WidgetActions.apply(synced, WidgetActions.ADD_BOOST, BoostType.BUILDER_POTION.name, now, s)
        assertEquals(BoostUse(BoostType.BUILDER_POTION, t0 + 7515, t0 + 7515 + 3600), more.boosts.last())

        val r1 = WidgetActions.addBoost(synced, BoostType.RESEARCH_POTION, now, s)
        val r2 = WidgetActions.addBoost(r1, BoostType.RESEARCH_POTION, now + 5, s)
        assertEquals(
            listOf(BoostUse(BoostType.RESEARCH_POTION, now, now + 3600), BoostUse(BoostType.RESEARCH_POTION, now + 3600, now + 7200)),
            r2.boosts.filter { it.type == BoostType.RESEARCH_POTION },
        )
        // Undo removes the queued hour, then cuts the running one off at the tap time.
        val u1 = WidgetActions.undoBoost(r2, BoostType.RESEARCH_POTION, now + 600)
        assertEquals(listOf(BoostUse(BoostType.RESEARCH_POTION, now, now + 3600)), u1.boosts.filter { it.type == BoostType.RESEARCH_POTION })
        val u2 = WidgetActions.undoBoost(u1, BoostType.RESEARCH_POTION, now + 600)
        assertEquals(listOf(BoostUse(BoostType.RESEARCH_POTION, now, now + 600)), u2.boosts.filter { it.type == BoostType.RESEARCH_POTION })
        assertEquals(u2, WidgetActions.undoBoost(u2, BoostType.RESEARCH_POTION, now + 700)) // nothing left
        assertEquals("Research potion undo (nothing to undo)",
            WidgetActions.describe(u2, u2, WidgetActions.UNDO_BOOST, BoostType.RESEARCH_POTION.name, names))

        // Bite runs in parallel with potions, not after them.
        val bite = WidgetActions.addBoost(synced, BoostType.BUILDER_BITE, now, s)
        assertEquals(BoostUse(BoostType.BUILDER_BITE, now, now + 3600), bite.boosts.last())
    }

    @Test fun pillsShowUndoOnlyWhileABoostRunsOrIsQueued() {
        val m = WidgetModelBuilder.build(synced, AppSettings(), names, SampleExports.times, t0 + 60)
        assertEquals("2h 5m" to true, m.builderPotion.text to m.builderPotion.canUndo)
        assertFalse(m.bite.canUndo)
        assertFalse(m.researchPotion.canUndo)
        assertEquals(RowButton.LAB_ASSISTANT, m.labRow.button)
        assertTrue(m.builderRows.filter { it.kind == RowKind.RUNNING }.all { it.button == RowButton.LINK })
    }

    // ---- Step 6: notifications ----

    @Test fun finishNotices() {
        val settings = AppSettings()
        assertEquals(t0 + 352, FinishNotices.nextFinish(synced, settings, t0))
        assertTrue(FinishNotices.check(synced, settings, names, t0 + 349).notices.isEmpty())

        val r = FinishNotices.check(synced, settings, names, t0 + 3000)
        assertEquals(
            listOf(
                FinishNotice(inferno.key, "Builder free: Inferno Tower", "Inferno Tower reached level 12."),
                FinishNotice(goblin.key, "Goblin Builder done: Wizard Tower", "Wizard Tower reached level 12."),
            ),
            r.notices,
        )
        assertEquals(setOf(inferno.key, goblin.key), r.notified)

        // Already announced → only the King is new at T0 + 5084.
        val marked = synced.copy(notified = r.notified)
        assertEquals(listOf("Builder free: Barbarian King"), FinishNotices.check(marked, settings, names, t0 + 5084).notices.map { it.title })

        // Something that finished more than 12 h ago is marked without a notification.
        val late = FinishNotices.check(synced, settings, names, t0 + 352 + FinishNotices.MAX_AGE_SEC + 1)
        assertFalse(late.notices.any { it.jobKey == inferno.key })
        assertTrue(inferno.key in late.notified)

        assertEquals(FinishNotice(lab.key, "Lab free", "Baby Dragon reached level 12."), FinishNotices.notice(lab, names))
        assertEquals(FinishNotice(pet.key, "Pet house free", "Angry Jelly reached level 10."), FinishNotices.notice(pet, names))
    }

    @Test fun syncResetsNotified() {
        val marked = synced.copy(notified = setOf(inferno.key))
        val resynced = (SyncLogic.apply(marked, text4) as SyncLogic.Outcome.Synced).state
        assertTrue(resynced.notified.isEmpty())
    }
}
