package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {

    private val text1 = SampleExports.text("export_1.json")
    private val text2 = SampleExports.text("export_2.json")

    private fun synced(old: AppState, text: String?): AppState {
        val o = SyncLogic.apply(old, text)
        assertTrue("expected success, got $o", o is SyncLogic.Outcome.Synced)
        return o.state
    }

    private fun failed(old: AppState, text: String?): SyncLogic.Outcome.Failed {
        val o = SyncLogic.apply(old, text)
        assertTrue("expected failure, got $o", o is SyncLogic.Outcome.Failed)
        return o as SyncLogic.Outcome.Failed
    }

    @Test fun firstSyncStoresExportAndHelperLinks() {
        val s = synced(AppState(), text2)
        assertEquals(export2.timestamp, s.export!!.timestamp)
        assertFalse(s.syncFailed)
        val module = export2.builderJobs.single { it.source == JobSource.MODULE }
        assertEquals(module.key, s.links.apprenticeJobKey)
        assertEquals(export2.labJob!!.key, s.links.labAssistantJobKey)
    }

    @Test fun invalidClipboardKeepsDataAndFlagsFailure() {
        val good = synced(AppState(), text1)
        for (bad in listOf(null, "", "   ", "hello", """{"foo":1}""")) {
            val f = failed(good, bad)
            assertTrue(f.state.syncFailed)
            assertEquals(good.exportJson, f.state.exportJson)
            assertEquals(good.links, f.state.links)
        }
        // A following good sync clears the flag.
        val recovered = synced(failed(good, "hello").state, text2)
        assertFalse(recovered.syncFailed)
    }

    @Test fun rejectsStaleExport() {
        val s2 = synced(AppState(), text2)
        val f = failed(s2, text1)
        assertTrue(f.reason.contains("older"))
        assertEquals(export2.timestamp, f.state.export!!.timestamp)
        // Same export again is fine.
        synced(s2, text2)
    }

    @Test fun manualLinkKeptOnlyIfSameJobStillExists() {
        // Export 1 has the Apprentice unlinked. The player links it to the 2nd Wizard Tower on the widget.
        val wt2 = export1.builderJobs.filter { it.dataId == 1000011 }[1]
        val firespitter = export1.builderJobs.first { it.dataId == 1000089 }
        val s1 = synced(AppState(), text1).copy(links = HelperLinks(apprenticeJobKey = wt2.key))
        // Still running in export 1 → kept on re-sync.
        assertEquals(wt2.key, synced(s1, text1).links.apprenticeJobKey)

        // Job that no longer exists → dropped.
        val gone = s1.copy(links = HelperLinks(apprenticeJobKey = "BUILDER:1000011:99#0"))
        assertNull(synced(gone, text1).links.apprenticeJobKey)

        // Export 2 has helper_recurrent on the module → overrides the manual link.
        val s2 = synced(s1.copy(links = HelperLinks(apprenticeJobKey = firespitter.key)), text2)
        assertEquals(export2.builderJobs.single { it.helperLinked }.key, s2.links.apprenticeJobKey)
    }

    @Test fun manualLinkFollowsSameJobWhenOccurrenceShifts() {
        // Two identical upgrades; the first finishes, so the old #1 becomes #0 in the next export.
        fun export(ts: Long, vararg timers: Int) =
            """{"tag":"#T","timestamp":$ts,"buildings":[""" +
                timers.joinToString(",") { """{"data":1000011,"lvl":16,"timer":$it}""" } + "]}"
        val s = synced(AppState(), export(100, 50, 500)).copy(links = HelperLinks("BUILDER:1000011:17#1"))
        val next = synced(s, export(200, 400))
        assertEquals("BUILDER:1000011:17#0", next.links.apprenticeJobKey)
    }

    @Test fun keepsOnlyPotionsEndingAfterNewT0() {
        val t2 = export2.timestamp
        val boosts = listOf(
            BoostUse(BoostType.BUILDER_POTION, t2 - 7200, t2 - 3600), // over → dropped
            BoostUse(BoostType.BUILDER_BITE, t2 - 3600, t2),          // ends exactly at T0 → dropped
            BoostUse(BoostType.RESEARCH_POTION, t2 - 1800, t2 + 1800), // overlaps T0 → kept
            BoostUse(BoostType.PET_POTION, t2 + 60, t2 + 3660),        // future → kept
        )
        val s = synced(synced(AppState(), text1).copy(boosts = boosts), text2)
        assertEquals(listOf(BoostType.RESEARCH_POTION, BoostType.PET_POTION), s.boosts.map { it.type })
    }

    @Test fun stateAndSettingsRoundTripThroughJson() {
        val s = synced(AppState(), text2).copy(
            boosts = listOf(BoostUse(BoostType.BUILDER_BITE, 1, 3601)),
            syncFailed = true,
        )
        val back = AppState.decode(s.encode())
        assertEquals(s, back)
        assertEquals(export2.jobs, back.export!!.jobs)

        val settings = AppSettings(totalBuildersOverride = 7, boosts = BoostSettings(builderBiteSavingSec = 1800))
        assertEquals(settings, AppSettings.decode(settings.encode()))

        // Missing or corrupt storage → defaults, never a crash.
        assertEquals(AppState(), AppState.decode(null))
        assertEquals(AppState(), AppState.decode("not json"))
        assertEquals(AppSettings(), AppSettings.decode("{\"unknownField\":1}"))
    }

    @Test fun totalBuildersSetting() {
        assertEquals(6, AppSettings().totalBuilders(export2))   // derived: 5 huts + B.O.B
        assertEquals(6, AppSettings().totalBuilders(null))      // default
        assertEquals(7, AppSettings(totalBuildersOverride = 7).totalBuilders(export2))
        // Never fewer than running builder jobs (6 in the sample).
        assertEquals(6, AppSettings(totalBuildersOverride = 4).totalBuilders(export2))
    }

    @Test fun outcomeStateIsTheOldStateObjectOnlyModifiedByFlag() {
        val good = synced(AppState(), text1)
        val f = failed(good, "nope")
        assertEquals(good.copy(syncFailed = true), f.state)
        assertSame(good.boosts, f.state.boosts)
    }
}
