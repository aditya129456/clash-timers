package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.export3
import com.aditya.clashtimer.core.SampleExports.names
import com.aditya.clashtimer.core.SampleExports.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export shapes first seen in `export_3.json`: guardians, the Goblin Builder, `builder_boost`. */
class Export3Test {

    private val t0 = 1793546851L
    private val text3 = SampleExports.text("export_3.json")

    private fun syncedFrom(old: AppState, text: String): AppState {
        val o = SyncLogic.apply(old, text)
        assertTrue("expected success, got $o", o is SyncLogic.Outcome.Synced)
        return o.state
    }

    // ---- Parser ----

    @Test fun header() {
        assertEquals(t0, export3.timestamp)
        assertEquals("Sun 01 Nov 15:27:31", utc(t0))
        assertEquals(18, export3.townHallLevel)
        assertEquals(6, export3.derivedTotalBuilders)
        assertTrue(export3.helpers.all { it.cooldown == 1331L })
    }

    @Test fun builderJobsIncludeGuardianHeroesAndGoblin() {
        data class J(val id: Int, val lvl: Int, val timer: Long, val source: JobSource, val goblin: Boolean)
        assertEquals(
            listOf(
                J(1000027, 11, 41921, JobSource.BUILDING, false),   // Inferno Tower
                J(1000067, 5, 239057, JobSource.BUILDING, false),   // Scattershot
                J(1000067, 5, 128802, JobSource.BUILDING, false),   // Scattershot
                J(1000011, 11, 67572, JobSource.BUILDING, true),    // Wizard Tower, "extra": true → Goblin
                J(28000000, 98, 89237, JobSource.HERO, false),      // Barbarian King
                J(28000004, 50, 309293, JobSource.HERO, false),     // Royal Champion
                J(107000008, 4, 396423, JobSource.GUARDIAN, false), // Logger
            ),
            export3.builderJobs.map { J(it.dataId, it.currentLevel, it.remainingAtT0, it.source, it.isGoblin) },
        )
        assertEquals(6, export3.regularBuilderJobs.size)
        assertTrue(export3.builderJobs.none { it.helperLinked })
        // Builder Base timer (buildings2, 90808 s) is still ignored.
        assertTrue(export3.jobs.none { it.remainingAtT0 == 90808L })
    }

    @Test fun helperRecurrentFalseIsNotALink() {
        val logger = export3.builderJobs.single { it.source == JobSource.GUARDIAN }
        assertFalse(logger.helperLinked)
        val e = (ExportParser.parse(
            """{"tag":"#A","timestamp":1,"buildings":[{"data":1000011,"lvl":3,"timer":9,"helper_recurrent":false}]}""",
        ) as ParseResult.Success).export
        assertFalse(e.builderJobs.single().helperLinked)
        assertTrue(e.helperOnFinishedItem.isEmpty())
    }

    @Test fun helperOnFinishedItemMeansIdle() {
        // Skeleton Spell (26000017) has helper_recurrent: true but no timer.
        assertTrue(export3.jobs.none { it.dataId == 26000017 })
        assertEquals(setOf(JobCategory.LAB), export3.helperOnFinishedItem)
        assertTrue(export1.helperOnFinishedItem.isEmpty())
        assertTrue(export2.helperOnFinishedItem.isEmpty())

        val lab = export3.labJob!!
        assertEquals(4000023, lab.dataId)
        assertEquals(566723L, lab.remainingAtT0)
        assertFalse(lab.helperLinked)
        assertEquals(73000011, export3.petJob!!.dataId)
        assertEquals(132593L, export3.petJob!!.remainingAtT0)
    }

    @Test fun builderBoost() {
        assertEquals(67L, export3.builderBoostRemaining)
        assertNull(export1.builderBoostRemaining) // only clocktower_* there
        assertNull(export2.builderBoostRemaining)
    }

    @Test fun labels() {
        assertEquals(
            listOf(
                "Inferno Tower → 12", "Scattershot → 6", "Scattershot → 6", "Wizard Tower → 12",
                "Barbarian King → 99", "Royal Champion → 51", "Logger → 5", "Baby Dragon → 12", "Angry Jelly → 10",
            ),
            export3.jobs.map(names::label),
        )
    }

    // ---- Sync ----

    @Test fun syncSetsBuilderPotionFromExportAndLeavesHelpersIdle() {
        val s = syncedFrom(AppState(), text3)
        assertEquals(listOf(BoostUse(BoostType.BUILDER_POTION, t0, t0 + 67)), s.boosts)
        assertNull(s.links.apprenticeJobKey)
        assertNull(s.links.labAssistantJobKey)
    }

    @Test fun exportBuilderBoostReplacesWidgetBuilderPotions() {
        val old = syncedFrom(AppState(), SampleExports.text("export_2.json")).copy(
            boosts = listOf(
                BoostUse(BoostType.BUILDER_POTION, t0 - 100, t0 + 3500),  // same potion, tapped on widget
                BoostUse(BoostType.BUILDER_POTION, t0 + 3500, t0 + 7100), // stacked one (in game: in builder_boost)
                BoostUse(BoostType.BUILDER_BITE, t0 - 100, t0 + 3500),    // different item → kept
                BoostUse(BoostType.RESEARCH_POTION, t0 - 100, t0 + 3500), // other category → kept
            ),
        )
        val s = syncedFrom(old, text3)
        assertEquals(
            listOf(
                BoostUse(BoostType.BUILDER_BITE, t0 - 100, t0 + 3500),
                BoostUse(BoostType.RESEARCH_POTION, t0 - 100, t0 + 3500),
                BoostUse(BoostType.BUILDER_POTION, t0, t0 + 67),
            ),
            s.boosts,
        )
    }

    @Test fun helperOnFinishedItemDropsManualLink() {
        fun export(ts: Long, spell: String) =
            """{"tag":"#T","timestamp":$ts,"buildings":[],
               "units":[{"data":4000008,"lvl":12,"timer":5000}],"spells":[$spell]}"""
        val dragonKey = "LAB:4000008:13#0"
        val linked = syncedFrom(AppState(), export(100, """{"data":26000017,"lvl":5}"""))
            .copy(links = HelperLinks(labAssistantJobKey = dragonKey))
        // No helper info in the export → the manual link is kept.
        assertEquals(
            dragonKey,
            syncedFrom(linked, export(200, """{"data":26000017,"lvl":5}""")).links.labAssistantJobKey,
        )
        // The game says the Assistant sits on a finished spell → idle.
        val idle = syncedFrom(linked, export(300, """{"data":26000017,"lvl":5,"helper_recurrent":true}"""))
        assertNull(idle.links.labAssistantJobKey)
    }

    // ---- Engine ----

    @Test fun builderPotionFromExportSpeedsUpAllBuilderJobsIncludingGoblin() {
        val s = syncedFrom(AppState(), text3)
        val timings = TimerEngine().computeAll(export3, s.links, s.boosts).associate { it.job to it.finishAt }
        // 67 s at 10× = 670 s of progress → every builder job finishes 603 s early.
        export3.builderJobs.forEach { assertEquals(t0 + it.remainingAtT0 - 603, timings.getValue(it)) }
        assertEquals(t0 + 566723, timings.getValue(export3.labJob!!))
        assertEquals(t0 + 132593, timings.getValue(export3.petJob!!))

        val noGoblinBoost = TimerEngine(BoostSettings(boostsApplyToGoblin = false))
        val goblin = export3.builderJobs.single { it.isGoblin }
        assertEquals(t0 + 67572, noGoblinBoost.finishTime(goblin, export3, s.links, s.boosts))
    }

    // ---- Widget ----

    private fun widget(now: Long) =
        WidgetModelBuilder.build(syncedFrom(AppState(), text3), AppSettings(), names, SampleExports.times, now)

    @Test fun widgetAtExportTime() {
        val m = widget(t0)
        assertEquals("Chief TH18", m.title)
        assertEquals("BUILDERS 6/6", m.buildersLabel)
        assertTrue(m.goblinWorking)
        assertEquals("2m", m.builderPotion.text) // 67 s, minutes rounded up
        assertTrue(m.builderPotion.active)
        assertEquals("Bite" to false, m.bite.text to m.bite.active)
        assertEquals("+1" to false, m.researchPotion.text to m.researchPotion.active)
        assertEquals("+1", m.petPotion.text)
        // Countdowns include the 67 s builder potion (603 s earlier for every builder job).
        assertEquals(
            listOf(
                "Inferno Tower → 12" to "11:28:38",
                "Wizard Tower → 12" to "18:36:09",
                "Barbarian King → 99" to "1d 00:37:14",
                "Scattershot → 6" to "1d 11:36:39",
                "Scattershot → 6" to "2d 18:14:14",
                "Royal Champion → 51" to "3d 13:44:50",
                "Logger → 5" to "4d 13:57:00",
            ),
            m.builderRows.map { it.label to Countdown.text(it.remaining) },
        )
        assertEquals(listOf(false, true, false, false, false, false, false), m.builderRows.map { it.goblin })
        assertTrue(m.builderRows.all { it.kind == RowKind.RUNNING && it.button == RowButton.LINK })
        assertEquals("Baby Dragon → 12" to "6d 13:25:23", m.labRow.let { it.label to Countdown.text(it.remaining) })
        assertEquals(RowButton.LINK, m.labRow.button)
        assertEquals("Angry Jelly → 10" to "1d 12:49:53", m.petRow.let { it.label to Countdown.text(it.remaining) })
        assertEquals(RowButton.NONE, m.petRow.button) // pets have no helper
        // Progress from the bundled game data: Inferno Tower 11 → 12 takes 13d 12h (1166400 s).
        assertEquals(1 - 41921 / 1166400f, m.builderRows[0].progress, 1e-4f)
        assertEquals(listOf(28000000), m.builderRows[2].iconIds)
        assertEquals(listOf("IT", "WT", "BK", "S", "S", "RC", "L"), m.builderRows.map { it.initials })
        assertEquals(
            listOf(FooterPart("Apprentice idle", FooterTone.IDLE), FooterPart(" · "), FooterPart("Assistant idle", FooterTone.IDLE)),
            m.footerLeft,
        )
        assertEquals("synced just now", m.footerRight)
    }

    @Test fun goblinRowDisappearsWhenDone() {
        val goblinDone = t0 + 67572 - 603
        val before = widget(goblinDone - 3) // within Finish.GRACE_SEC it is already done
        assertEquals("BUILDERS 5/6", before.buildersLabel) // Inferno already done
        assertTrue(before.goblinWorking)
        assertEquals("0:00:03", Countdown.text(before.builderRows.single { it.goblin }.remaining))
        assertEquals("1m", before.builderRows.single { it.goblin }.staticStatus)

        val after = widget(goblinDone)
        assertEquals("BUILDERS 5/6", after.buildersLabel)
        assertFalse(after.goblinWorking)
        assertTrue(after.builderRows.none { it.goblin })
        assertEquals(6, after.builderRows.size) // free builders get no extra rows
        val inferno = after.builderRows.first()
        assertEquals(RowKind.COMPLETED, inferno.kind)
        assertEquals("Inferno Tower → 12" to "Completed", inferno.label to inferno.staticStatus)
        assertEquals(RowButton.NONE, inferno.button)
        assertFalse(after.builderPotion.active) // potion long over
    }

    @Test fun activeBoostPills() {
        val b = listOf(
            BoostUse(BoostType.BUILDER_POTION, 0, 3600),
            BoostUse(BoostType.BUILDER_POTION, 3600, 7200), // stacked back-to-back
            BoostUse(BoostType.BUILDER_BITE, 100, 3700),
            BoostUse(BoostType.PET_POTION, 5000, 8600),     // not started yet
        )
        assertEquals("1h 50m", BoostPill(BoostType.BUILDER_POTION, WidgetModelBuilder.boostLeft(b, BoostType.BUILDER_POTION, 600)).text)
        assertEquals("52m", BoostPill(BoostType.BUILDER_BITE, WidgetModelBuilder.boostLeft(b, BoostType.BUILDER_BITE, 600)).text)
        assertNull(WidgetModelBuilder.boostLeft(b, BoostType.PET_POTION, 600))
        assertNull(WidgetModelBuilder.boostLeft(b, BoostType.RESEARCH_POTION, 600))
        assertEquals("2h", WidgetModelBuilder.formatPill(7200))
        assertEquals("1m", WidgetModelBuilder.formatPill(1))
    }

    @Test fun nextUpdateFollowsPillMinutesAndSyncAge() {
        // 67 s potion → the pill drops from 2m to 1m at T0 + 7.
        assertEquals((t0 + 7) * 1000, WidgetSchedule.nextUpdateMs(widget(t0), t0 * 1000, liveCountdown = true))
        // After the potion: "synced 1m ago" → "2m ago" at T0 + 120 comes before any countdown edge.
        assertEquals((t0 + 120) * 1000, WidgetSchedule.nextUpdateMs(widget(t0 + 100), (t0 + 100) * 1000, liveCountdown = true))
    }

    @Test fun describeOutcome() {
        val o = SyncLogic.apply(AppState(), text3)
        assertEquals(
            "Synced TH18 · 6 builder jobs + Goblin · lab: Baby Dragon → 12 · pet: Angry Jelly → 10 · builder potion 2m left",
            o.describe(names),
        )
        assertEquals("Sync failed: Clipboard is empty", SyncLogic.apply(AppState(), "").describe(names))
    }

    @Test fun syncLogKeepsNewestFirstAndCaps() {
        var log = SyncLog()
        val n = SyncLog.MAX_ENTRIES + 6 // newest index n - 1 is odd: a result, not a breadcrumb
        repeat(n) { log = log.add(SyncLogEntry(it.toLong(), "app", if (it % 2 == 0) null else true, "m$it")) }
        assertEquals(SyncLog.MAX_ENTRIES, log.entries.size)
        assertEquals(n - 1L, log.entries.first().at)
        assertEquals(n - 1L, log.lastResult!!.at)
        assertEquals(log, SyncLog.decode(log.encode()))
        assertEquals(SyncLog(), SyncLog.decode("garbage"))
    }
}
