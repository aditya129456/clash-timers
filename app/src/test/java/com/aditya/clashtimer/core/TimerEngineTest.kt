package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val H = 3600L

class TimerEngineTest {

    private val engine = TimerEngine()
    private val noLinks = HelperLinks()

    private val T0 = 1_000_000L

    /** Synthetic export with Apprentice lvl 8 / Lab Assistant lvl 12 and the given jobs. */
    private fun export(cooldown: Long, vararg jobs: Job) = VillageExport(
        tag = "#T", timestamp = T0, townHallLevel = 18, builderHutCount = 5,
        helpers = listOf(
            Helper(GameIds.HELPER_BUILDERS_APPRENTICE, 8, cooldown),
            Helper(GameIds.HELPER_LAB_ASSISTANT, 12, cooldown),
        ),
        jobs = jobs.toList(),
    )

    private fun builder(remaining: Long, id: Int = 1, occ: Int = 0) =
        Job(JobCategory.BUILDER, JobSource.BUILDING, id, 5, remaining, false, occurrence = occ)

    private fun lab(remaining: Long) = Job(JobCategory.LAB, JobSource.UNIT, 4000008, 12, remaining, false)
    private fun pet(remaining: Long) = Job(JobCategory.PET, JobSource.PET, 73000010, 7, remaining, false)

    // ---------------------------------------------------------------------------------------
    // Reference cases from export_2.json
    // ---------------------------------------------------------------------------------------

    @Test fun referenceDragonWithLabAssistant() {
        val e = export2
        val dragon = e.labJob!!
        val links = HelperLinks.fromExport(e)

        val finish = engine.finishTime(dragon, e, links, emptyList())
        assertEquals(e.timestamp + 78685, finish)
        assertEquals("Thu 15 Oct 13:36:18", utc(finish))

        val noHelper = engine.finishTime(dragon, e, noLinks, emptyList())
        assertEquals(e.timestamp + 121885, noHelper)
        assertEquals("Fri 16 Oct 01:36:18", utc(noHelper))
    }

    @Test fun referenceModuleWithApprentice() {
        val e = export2
        val module = e.builderJobs.single { it.source == JobSource.MODULE }
        val links = HelperLinks.fromExport(e)

        // At the helper start (T0 + 72189) 21760 s remain; at 9× that takes 2417.8 s.
        // => T0 + 74606.8 → rounded up to T0 + 74607 (Thu 15 Oct 12:28:20 UTC).
        val finish = engine.finishTime(module, e, links, emptyList())
        assertEquals(e.timestamp + 74607, finish)
        assertEquals("Thu 15 Oct 12:28:20", utc(finish))

        val noHelper = engine.finishTime(module, e, noLinks, emptyList())
        assertEquals(e.timestamp + 93949, noHelper)
        assertEquals("Thu 15 Oct 17:50:42", utc(noHelper))
    }

    @Test fun computeAllOnExport2() {
        val timings = engine.computeAll(export2, HelperLinks.fromExport(export2), emptyList())
        assertEquals(export2.jobs.size, timings.size)
        // Unlinked jobs finish at T0 + timer exactly.
        timings.filter { !it.job.helperLinked }.forEach {
            assertEquals(export2.timestamp + it.job.remainingAtT0, it.finishAt)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cross-export: simulate from export 1 to export 2's timestamp
    // ---------------------------------------------------------------------------------------

    @Test fun simulatingExport1ForwardPredictsExport2() {
        val e1 = export1
        val t2 = export2.timestamp
        val module1 = e1.builderJobs.single { it.source == JobSource.MODULE }
        // Between exports the player linked the Apprentice to the module and ate one Builder Bite.
        val links = HelperLinks(apprenticeJobKey = module1.key, labAssistantJobKey = e1.labJob!!.key)
        val bite = BoostStacking.add(emptyList(), BoostType.BUILDER_BITE, e1.timestamp + 1000, H)

        for ((j1, j2) in e1.jobs.zip(export2.jobs)) {
            assertEquals(j1.key, j2.key)
            val predicted = engine.remainingAt(j1, e1, links, bite, t2)
            val tolerance = if (j1.key == module1.key || j1.category == JobCategory.LAB) 60L else 0L
            assertTrue(
                "${j1.key}: predicted $predicted, actual ${j2.remainingAtT0}",
                abs(predicted - j2.remainingAtT0) <= tolerance,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Potions and Builder Bite
    // ---------------------------------------------------------------------------------------

    @Test fun builderPotionAppliesToAllBuilderJobsOnly() {
        val a = builder(30 * H, id = 1)
        val b = builder(5 * H, id = 2)
        val l = lab(30 * H)
        val e = export(cooldown = 100 * H, a, b, l)
        val potions = BoostStacking.add(emptyList(), BoostType.BUILDER_POTION, T0, H)

        assertEquals(T0 + 21 * H, engine.finishTime(a, e, noLinks, potions)) // 30h − 9h
        assertEquals(T0 + H / 2, engine.finishTime(b, e, noLinks, potions))  // 5h at 10× = 30 min
        assertEquals(T0 + 30 * H, engine.finishTime(l, e, noLinks, potions)) // lab unaffected
    }

    @Test fun potionsStackBackToBack() {
        var p = BoostStacking.add(emptyList(), BoostType.BUILDER_POTION, T0, H)
        p = BoostStacking.add(p, BoostType.BUILDER_POTION, T0 + 600, H)   // tapped while active
        p = BoostStacking.add(p, BoostType.BUILDER_POTION, T0 + 700, H)   // queued behind both
        assertEquals(
            listOf(T0 to T0 + H, T0 + H to T0 + 2 * H, T0 + 2 * H to T0 + 3 * H),
            p.map { it.start to it.end },
        )
        // After all windows ended, a new tap starts at the tap time.
        val later = BoostStacking.add(p, BoostType.BUILDER_POTION, T0 + 10 * H, H)
        assertEquals(T0 + 10 * H, later.last().start)

        // Different types don't queue behind each other.
        val withBite = BoostStacking.add(p, BoostType.BUILDER_BITE, T0 + 600, H)
        assertEquals(T0 + 600, withBite.last().start)

        // 3 stacked hours at 10× on a 40 h job: 30 h done in 3 h, 10 h left at 1×.
        val job = builder(40 * H)
        assertEquals(T0 + 13 * H, engine.finishTime(job, export(100 * H, job), noLinks, p))
    }

    @Test fun undoRemovesLastPotionOfThatType() {
        var p = BoostStacking.add(emptyList(), BoostType.RESEARCH_POTION, T0, H)
        p = BoostStacking.add(p, BoostType.RESEARCH_POTION, T0 + 60, H)
        p = BoostStacking.add(p, BoostType.PET_POTION, T0 + 60, H)
        p = BoostStacking.removeLast(p, BoostType.RESEARCH_POTION)
        assertEquals(listOf(BoostType.RESEARCH_POTION to T0, BoostType.PET_POTION to T0 + 60), p.map { it.type to it.start })
        assertEquals(p, BoostStacking.removeLast(p, BoostType.BUILDER_BITE))
    }

    @Test fun researchAndPetPotions() {
        val l = lab(48 * H)
        val pt = pet(48 * H)
        val e = export(100 * H, l, pt)
        val boosts = listOf(BoostUse(BoostType.RESEARCH_POTION, T0, T0 + H), BoostUse(BoostType.PET_POTION, T0, T0 + H))
        assertEquals(T0 + 25 * H, engine.finishTime(l, e, noLinks, boosts))  // 48 − 23
        assertEquals(T0 + 25 * H, engine.finishTime(pt, e, noLinks, boosts))
    }

    @Test fun builderBiteIsTwoX() {
        val job = builder(10 * H)
        val e = export(100 * H, job)
        val bite = listOf(BoostUse(BoostType.BUILDER_BITE, T0 + H, T0 + 2 * H))
        assertEquals(T0 + 9 * H, engine.finishTime(job, e, noLinks, bite))
    }

    @Test fun onlyBoostTimeAfterT0Counts() {
        val job = builder(20 * H)
        val e = export(100 * H, job)
        // Started 30 min before T0: only the 30 min after T0 counts → saves 4.5 h.
        val overlapping = listOf(BoostUse(BoostType.BUILDER_POTION, T0 - H / 2, T0 + H / 2))
        assertEquals(T0 + 20 * H - 9 * H / 2, engine.finishTime(job, e, noLinks, overlapping))
        // Entirely before T0: already in `timer`, ignored.
        val past = listOf(BoostUse(BoostType.BUILDER_POTION, T0 - 2 * H, T0 - H))
        assertEquals(T0 + 20 * H, engine.finishTime(job, e, noLinks, past))
        // Ends exactly at T0: ignored.
        val endsAtT0 = listOf(BoostUse(BoostType.BUILDER_POTION, T0 - H, T0))
        assertEquals(T0 + 20 * H, engine.finishTime(job, e, noLinks, endsAtT0))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers, and helpers stacking with potions
    // ---------------------------------------------------------------------------------------

    @Test fun apprenticeOnlyBoostsLinkedJob() {
        val linked = builder(30 * H, id = 1)
        val other = builder(30 * H, id = 2)
        val e = export(cooldown = 2 * H, linked, other)
        val links = HelperLinks(apprenticeJobKey = linked.key)
        assertEquals(T0 + 22 * H, engine.finishTime(linked, e, links, emptyList()))  // 30 − 8
        assertEquals(T0 + 30 * H, engine.finishTime(other, e, links, emptyList()))
    }

    @Test fun helperRepeatsWithTheConfiguredCycle() {
        val job = builder(72 * H)
        val e = export(cooldown = H, job)
        val links = HelperLinks(apprenticeJobKey = job.key)
        // 24 h cycle: helper at T0+1h, T0+25h, T0+49h, each saves 8 h. Progress = t + 8 h per completed
        // window; at T0+49h it is 65 h, and the 7 h left run at 9× in the third window → 2800 s.
        val daily = TimerEngine(BoostSettings(helperPeriodSec = 24 * H))
        assertEquals(T0 + 49 * H + 2800, daily.finishTime(job, e, links, emptyList()))
        // Default 23 h cycle: windows at T0+1h, T0+24h, T0+47h. At T0+47h progress is
        // 63 h; the last 9 h take exactly the third window.
        assertEquals(T0 + 48 * H, engine.finishTime(job, e, links, emptyList()))
    }

    @Test fun helperSavingFollowsExportLevelAndSettingsOverride() {
        val job = lab(20 * H)
        val e = export(cooldown = 0, job)
        val links = HelperLinks(labAssistantJobKey = job.key)
        assertEquals(T0 + 8 * H, engine.finishTime(job, e, links, emptyList()))  // lvl 12: 13 h in hour 1, then 7 h
        val custom = TimerEngine(BoostSettings(labAssistantSavingSecOverride = 5 * H))
        assertEquals(T0 + 15 * H, custom.finishTime(job, e, links, emptyList()))
    }

    @Test fun builderPotionPlusApprenticeStackAdditively() {
        // Rate = 1 + 9 (potion) + 8 (Apprentice lvl 8) = 18× while both are active.
        // The measured data say the helper hour runs at (lvl + 1)× = 9×, not 8× total.
        val cooldown = 2 * H
        val linked = builder(cooldown + 18 * H + 1000, id = 1)
        val other = builder(cooldown + 18 * H + 1000, id = 2)
        val e = export(cooldown, linked, other)
        val links = HelperLinks(apprenticeJobKey = linked.key)
        val potion = listOf(BoostUse(BoostType.BUILDER_POTION, T0 + cooldown, T0 + cooldown + H))

        assertEquals(T0 + cooldown + H + 1000, engine.finishTime(linked, e, links, potion))
        // Unlinked job only gets the potion: 18h+1000 − 9h.
        assertEquals(T0 + cooldown + 9 * H + 1000, engine.finishTime(other, e, links, potion))
    }

    @Test fun researchPotionPlusLabAssistantPartialOverlap() {
        // LA window [T0+1h, T0+2h) at +12; potion [T0+1.5h, T0+2.5h) at +23.
        // T0..1h: 1h. 1h..1.5h: 13× → 6.5h. 1.5h..2h: 36× → 18h. 2h..2.5h: 24× → 12h. Total 37.5h by T0+2.5h.
        val job = lab(40 * H)
        val e = export(cooldown = H, job)
        val links = HelperLinks(labAssistantJobKey = job.key)
        val potion = listOf(BoostUse(BoostType.RESEARCH_POTION, T0 + 3 * H / 2, T0 + 5 * H / 2))
        assertEquals(T0 + 5 * H / 2 + 5 * H / 2, engine.finishTime(job, e, links, potion))
        assertEquals(5 * H / 2, engine.remainingAt(job, e, links, potion, T0 + 5 * H / 2))
    }

    @Test fun builderBitePotionAndApprenticeAllStack() {
        // 1 + 1 + 9 + 8 = 19× for one hour.
        val job = builder(19 * H + 500)
        val e = export(cooldown = 0, job)
        val links = HelperLinks(apprenticeJobKey = job.key)
        val boosts = listOf(BoostUse(BoostType.BUILDER_BITE, T0, T0 + H), BoostUse(BoostType.BUILDER_POTION, T0, T0 + H))
        assertEquals(T0 + H + 500, engine.finishTime(job, e, links, boosts))
    }

    // ---------------------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------------------

    @Test fun finishedJobFinishesAtT0() {
        val job = builder(0)
        assertEquals(T0, engine.finishTime(job, export(H, job), noLinks, emptyList()))
    }

    @Test fun finishesMidWindow() {
        val job = builder(5 * H)
        val e = export(cooldown = 0, job)
        val links = HelperLinks(apprenticeJobKey = job.key)
        // 5h at 9× → 2000 s, entirely inside the first helper window.
        assertEquals(T0 + 2000, engine.finishTime(job, e, links, emptyList()))
        assertEquals(0L, engine.remainingAt(job, e, links, emptyList(), T0 + 3000))
    }

    @Test fun veryLongJobWithDailyHelperTerminates() {
        val job = builder(365 * 24 * H)
        val e = export(cooldown = 0, job)
        val links = HelperLinks(apprenticeJobKey = job.key)
        val finish = engine.finishTime(job, e, links, emptyList())
        // 32 h progress per 24 h day, so ~274 days.
        assertTrue(finish > T0 && finish < T0 + 275 * 24 * H)
    }
}
