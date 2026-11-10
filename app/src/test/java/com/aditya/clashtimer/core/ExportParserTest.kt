package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExportParserTest {

    // ---- Validation ----

    private fun assertInvalid(text: String) {
        assertTrue("expected invalid: $text", ExportParser.parse(text) is ParseResult.Invalid)
    }

    @Test fun rejectsNonVillageClipboardContents() {
        assertInvalid("")
        assertInvalid("hello")
        assertInvalid("[1,2,3]")
        assertInvalid("{}")
        assertInvalid("""{"timestamp":1,"buildings":[]}""")                     // no tag
        assertInvalid("""{"tag":"TEST000","timestamp":1,"buildings":[]}""")   // tag without #
        assertInvalid("""{"tag":123,"timestamp":1,"buildings":[]}""")           // tag not a string
        assertInvalid("""{"tag":"#A","timestamp":"1","buildings":[]}""")        // timestamp not a number
        assertInvalid("""{"tag":"#A","buildings":[]}""")                        // no timestamp
        assertInvalid("""{"tag":"#A","timestamp":1}""")                         // no buildings
        assertInvalid("""{"tag":"#A","timestamp":1,"buildings":{}}""")          // buildings not array
        assertInvalid(SampleExports.text("export_1.json").dropLast(10))    // truncated copy
    }

    @Test fun acceptsMinimalExportAndSurroundingWhitespace() {
        val r = ExportParser.parse("\n  {\"tag\":\"#A\",\"timestamp\":5,\"buildings\":[]}  \n")
        assertTrue(r is ParseResult.Success)
        val e = (r as ParseResult.Success).export
        assertEquals(5L, e.timestamp)
        assertTrue(e.jobs.isEmpty())
        assertNull(e.townHallLevel)
        assertNull(e.nextHelperActivation)
    }

    // ---- Sample export 1 ----

    @Test fun export1Header() {
        assertEquals("#TEST000", export1.tag)
        assertEquals(1791934606L, export1.timestamp)
        assertEquals(18, export1.townHallLevel)
        assertEquals(5, export1.builderHutCount) // 2 + 3
        assertTrue(export1.hasBobsHut)
        assertEquals(6, export1.derivedTotalBuilders) // 5 huts + B.O.B = 6 busy builders in the sample
    }

    @Test fun export1Helpers() {
        assertEquals(4, export1.helpers.size)
        assertEquals(8, export1.helper(GameIds.HELPER_BUILDERS_APPRENTICE)!!.level)
        assertEquals(12, export1.helper(GameIds.HELPER_LAB_ASSISTANT)!!.level)
        assertEquals(5, export1.helper(GameIds.HELPER_PROSPECTOR)!!.level)
        assertEquals(1, export1.helper(GameIds.HELPER_ALCHEMIST_GUESS)!!.level)
        assertTrue(export1.helpers.all { it.cooldown == 45285L })
        assertEquals(1791934606L + 45285L, export1.nextHelperActivation)
    }

    @Test fun export1BuilderJobs() {
        val b = export1.builderJobs
        assertEquals(
            listOf(
                Triple(1000011, 16, 160289L),  // Wizard Tower → 17
                Triple(1000011, 16, 170722L),  // Wizard Tower → 17
                Triple(1000089, 2, 942970L),   // Firespitter → 3
                Triple(1000089, 2, 660133L),   // Firespitter → 3
                Triple(102000040, 7, 184442L), // crafted defense module → 8
                Triple(1000102, 0, 519045L),   // Super Wizard Tower (new)
            ),
            b.map { Triple(it.dataId, it.currentLevel, it.remainingAtT0) },
        )
        // Mortar with gear_up is not a job.
        assertFalse(export1.jobs.any { it.dataId == 1000013 })
        // No hero or trap is upgrading in this sample.
        assertTrue(b.none { it.source == JobSource.HERO || it.source == JobSource.TRAP })
        assertTrue("Apprentice unlinked in export 1", b.none { it.helperLinked })
    }

    @Test fun export1NestedModuleAndNewBuilding() {
        val module = export1.builderJobs.single { it.source == JobSource.MODULE }
        assertEquals(102000040, module.dataId)
        assertEquals(1000097, module.parentDataId)
        assertEquals(103000013, module.typeDataId)
        assertEquals(8, module.targetLevel)

        val swt = export1.builderJobs.single { it.dataId == 1000102 }
        assertTrue(swt.isNewBuilding)
        assertEquals(1, swt.targetLevel)
    }

    @Test fun export1LabAndPetJobs() {
        assertEquals(1, export1.labJobs.size)
        val dragon = export1.labJob!!
        assertEquals(4000008, dragon.dataId)
        assertEquals(JobSource.UNIT, dragon.source)
        assertEquals(13, dragon.targetLevel)
        assertEquals(223206L, dragon.remainingAtT0)
        assertTrue(dragon.helperLinked)

        assertEquals(1, export1.petJobs.size)
        val pet = export1.petJob!!
        assertEquals(73000010, pet.dataId)
        assertEquals(8, pet.targetLevel)
        assertEquals(298403L, pet.remainingAtT0)
        assertFalse(pet.helperLinked)
    }

    @Test fun duplicateUpgradesGetDistinctKeys() {
        val keys = export1.jobs.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            listOf("BUILDER:1000011:17#0", "BUILDER:1000011:17#1"),
            export1.builderJobs.filter { it.dataId == 1000011 }.map { it.key },
        )
    }

    // ---- Sample export 2 ----

    @Test fun export2HeaderAndHelpers() {
        assertEquals(1791992693L, export2.timestamp)
        assertEquals(18, export2.townHallLevel)
        assertTrue(export2.helpers.all { it.cooldown == 72189L })
        // Next helper hour: Thu 15 Oct 11:48:02 UTC
        assertEquals("Thu 15 Oct 11:48:02", utc(export2.nextHelperActivation!!))
        assertEquals("Wed 14 Oct 15:44:53", utc(export2.timestamp))
    }

    @Test fun export2Jobs() {
        assertEquals(
            listOf(98602L, 109035L, 881283L, 598446L, 93949L, 457358L),
            export2.builderJobs.map { it.remainingAtT0 },
        )
        val module = export2.builderJobs.single { it.source == JobSource.MODULE }
        assertTrue("helper_recurrent nested in types[].modules[]", module.helperLinked)
        assertEquals(1, export2.builderJobs.count { it.helperLinked })

        assertEquals(121885L, export2.labJob!!.remainingAtT0)
        assertTrue(export2.labJob!!.helperLinked)
        assertEquals(240316L, export2.petJob!!.remainingAtT0)

        val links = HelperLinks.fromExport(export2)
        assertEquals(module.key, links.apprenticeJobKey)
        assertEquals(export2.labJob!!.key, links.labAssistantJobKey)
        assertNull(HelperLinks.fromExport(export1).apprenticeJobKey)
    }

    // ---- Builder Base and other sections ----

    @Test fun ignoresBuilderBaseAndPicksUpHeroTrapSpellSiegeTimers() {
        val text = """
            {"tag":"#X","timestamp":100,
             "buildings":[{"data":1000001,"lvl":17,"cnt":1}],
             "traps":[{"data":12000000,"lvl":3,"timer":50}],
             "heroes":[{"data":28000000,"lvl":98,"timer":60,"helper_recurrent":true}],
             "spells":[{"data":26000000,"lvl":4,"timer":70}],
             "siege_machines":[{"data":4000051,"lvl":2,"timer":80}],
             "buildings2":[{"data":1000033,"lvl":5,"timer":999}],
             "units2":[{"data":4000031,"lvl":5,"timer":999}],
             "heroes2":[{"data":28000003,"lvl":24,"timer":999}],
             "boosts":{"clocktower_boost":1808}}
        """.trimIndent()
        val e = (ExportParser.parse(text) as ParseResult.Success).export
        assertEquals(17, e.townHallLevel)
        assertTrue(e.jobs.none { it.remainingAtT0 == 999L })
        assertEquals(listOf(JobSource.TRAP, JobSource.HERO), e.builderJobs.map { it.source })
        assertTrue(e.builderJobs.single { it.source == JobSource.HERO }.helperLinked)
        assertEquals(listOf(JobSource.SPELL, JobSource.SIEGE_MACHINE), e.labJobs.map { it.source })
        assertNotNull(e.labJob)
    }

    // ---- Cross-export ----

    @Test fun crossExportTimerDrops() {
        val elapsed = export2.timestamp - export1.timestamp
        assertEquals(58087L, elapsed)

        fun extra(j1: Job, j2: Job) = (j1.remainingAtT0 - j2.remainingAtT0) - elapsed

        val dragonExtra = extra(export1.labJob!!, export2.labJob!!)
        assertEquals(101321L, export1.labJob!!.remainingAtT0 - export2.labJob!!.remainingAtT0)
        assertEquals(43234L, dragonExtra)
        assertTrue("Lab Assistant ≈ 12 h", abs(dragonExtra - 12 * 3600) <= 60)

        val m1 = export1.builderJobs.single { it.source == JobSource.MODULE }
        val m2 = export2.builderJobs.single { it.source == JobSource.MODULE }
        assertEquals(90493L, m1.remainingAtT0 - m2.remainingAtT0)
        assertEquals(32406L, extra(m1, m2))
        assertTrue("Apprentice 8 h + Builder Bite 1 h", abs(extra(m1, m2) - 9 * 3600) <= 60)

        // Every other builder job got exactly the 1 h Builder Bite; the pet got nothing extra.
        export1.builderJobs.zip(export2.builderJobs)
            .filter { (a, _) -> a.source != JobSource.MODULE }
            .forEach { (a, b) -> assertEquals(a.key, b.key); assertEquals(3600L, extra(a, b)) }
        assertEquals(0L, extra(export1.petJob!!, export2.petJob!!))
    }
}
