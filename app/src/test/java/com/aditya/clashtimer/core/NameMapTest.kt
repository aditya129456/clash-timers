package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.names
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMapTest {

    @Test fun assetLoads() {
        assertTrue("expected ~200 home-village names, got ${names.size}", names.size >= 190)
        assertEquals("Town Hall", names.name(1000001))
        assertEquals("Builder's Hut", names.name(GameIds.BUILDERS_HUT))
        assertEquals("B.O.B's Hut", names.name(GameIds.BOBS_HUT))
        assertEquals("Builder's Apprentice", names.name(GameIds.HELPER_BUILDERS_APPRENTICE))
        assertEquals("Lab Assistant", names.name(GameIds.HELPER_LAB_ASSISTANT))
        assertEquals("Prospector", names.name(GameIds.HELPER_PROSPECTOR))
    }

    /** Every upgradable thing in both exports must have a real name (helpers 93000003 is not shown). */
    @Test fun everyIdInBothExportsHasAName() {
        val sections = listOf(
            "buildings", "traps", "heroes", "units", "spells", "siege_machines", "pets", "guardians", "equipment",
        )
        for (file in listOf("export_1.json", "export_2.json", "export_3.json")) {
            val root = Json.parseToJsonElement(SampleExports.text(file)).jsonObject
            val ids = mutableSetOf<Int>()
            fun walk(e: Any?) {
                when (e) {
                    is JsonArray -> e.forEach(::walk)
                    is JsonObject -> {
                        (e["data"] as? JsonPrimitive)?.intOrNull?.let(ids::add)
                        e.values.forEach(::walk)
                    }
                }
            }
            sections.forEach { walk(root[it]) }
            val missing = ids.filterNot(names::has)
            assertTrue("$file: no name for $missing", missing.isEmpty())
            assertTrue(ids.size > 100)
        }
    }

    @Test fun jobLabelsForExport2() {
        assertEquals(
            listOf(
                "Wizard Tower → 17",
                "Wizard Tower → 17",
                "Firespitter → 3",
                "Firespitter → 3",
                "Cake-A-Pult Damage → 8",
                "Super Wizard Tower (new)",
                "Dragon → 13",
                "Spirit Fox → 8",
            ),
            export2.jobs.map(names::label),
        )
        assertEquals(export1.jobs.map(names::label), export2.jobs.map(names::label))
    }

    @Test fun unknownIdsFallBack() {
        val job = Job(JobCategory.BUILDER, JobSource.BUILDING, 1000999, 3, 100, false)
        assertEquals("Unknown (1000999) → 4", names.label(job))
        assertEquals("Unknown (1000999) (new)", names.label(job.copy(currentLevel = 0)))
        val module = Job(JobCategory.BUILDER, JobSource.MODULE, 102000999, 1, 100, false, 1000097, 103000999)
        assertEquals("Unknown (103000999) Unknown (102000999) → 2", names.label(module))
    }

    @Test fun acceptsFlatMapToo() {
        val m = NameMap.fromJson("""{"1":"A","x":"ignored","2":3}""")
        assertEquals(1, m.size)
        assertEquals("A", m.name(1))
    }
}
