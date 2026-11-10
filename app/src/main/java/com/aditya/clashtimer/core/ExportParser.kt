package com.aditya.clashtimer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses the Clash of Clans village data export (Settings → Data Export → Copy).
 * Pure Kotlin, no Android dependencies. Builder Base sections (`*2`, `boosts`) are ignored.
 */
object ExportParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): ParseResult {
        val root = try {
            json.parseToJsonElement(text.trim())
        } catch (e: Exception) {
            return ParseResult.Invalid("Not JSON")
        }
        if (root !is JsonObject) return ParseResult.Invalid("Not a JSON object")

        val tag = (root["tag"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (tag == null || !tag.startsWith("#")) return ParseResult.Invalid("Missing or invalid tag")
        val timestamp = (root["timestamp"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: return ParseResult.Invalid("Missing or invalid timestamp")
        val buildings = root["buildings"] as? JsonArray
            ?: return ParseResult.Invalid("Missing buildings")

        val jobs = mutableListOf<Job>()
        val helperOnFinished = mutableSetOf<JobCategory>()

        /** Adds a job if [obj] has a `timer`; a helper link on an item without one means the helper is idle. */
        fun visit(obj: JsonObject, category: JobCategory, source: JobSource, parentId: Int? = null, typeId: Int? = null) {
            val job = obj.toJob(category, source, parentId, typeId)
            if (job != null) jobs += job
            else if (obj.flag("helper_recurrent")) helperOnFinished += category
        }
        fun visitAll(section: String, category: JobCategory, source: JobSource) =
            root.array(section).objects().forEach { visit(it, category, source) }

        // Builder jobs: buildings (incl. nested crafted-defense modules), traps, heroes, guardians.
        for (b in buildings.objects()) {
            visit(b, JobCategory.BUILDER, JobSource.BUILDING)
            val parentId = b.int("data")
            for (type in b.array("types").objects()) {
                val typeId = type.int("data")
                for (module in type.array("modules").objects()) {
                    visit(module, JobCategory.BUILDER, JobSource.MODULE, parentId, typeId)
                }
            }
        }
        visitAll("traps", JobCategory.BUILDER, JobSource.TRAP)
        visitAll("heroes", JobCategory.BUILDER, JobSource.HERO)
        visitAll("guardians", JobCategory.BUILDER, JobSource.GUARDIAN)

        // Lab: units, spells, siege machines.
        visitAll("units", JobCategory.LAB, JobSource.UNIT)
        visitAll("spells", JobCategory.LAB, JobSource.SPELL)
        visitAll("siege_machines", JobCategory.LAB, JobSource.SIEGE_MACHINE)

        // Pet house.
        visitAll("pets", JobCategory.PET, JobSource.PET)

        // Active home-village builder potion (Builder Base `clocktower_*` is ignored).
        val builderBoost = (root["boosts"] as? JsonObject)?.long("builder_boost")?.takeIf { it > 0 }

        val townHall = buildings.objects().firstOrNull { it.int("data") == GameIds.TOWN_HALL }?.int("lvl")
        val huts = buildings.objects()
            .filter { it.int("data") == GameIds.BUILDERS_HUT }
            .sumOf { it.int("cnt") ?: 1 }
        val bobsHut = buildings.objects().any { it.int("data") == GameIds.BOBS_HUT }

        val helpers = root.array("helpers").objects().mapNotNull { h ->
            val id = h.int("data") ?: return@mapNotNull null
            Helper(dataId = id, level = h.int("lvl") ?: 0, cooldown = h.long("helper_cooldown") ?: 0L)
        }

        return ParseResult.Success(
            VillageExport(
                tag = tag,
                timestamp = timestamp,
                townHallLevel = townHall,
                builderHutCount = huts,
                hasBobsHut = bobsHut,
                helpers = helpers,
                jobs = assignOccurrences(jobs),
                helperOnFinishedItem = helperOnFinished,
                builderBoostRemaining = builderBoost,
            )
        )
    }

    private fun assignOccurrences(jobs: List<Job>): List<Job> {
        val seen = HashMap<Triple<JobCategory, Int, Int>, Int>()
        return jobs.map { job ->
            val k = Triple(job.category, job.dataId, job.targetLevel)
            val n = seen.getOrDefault(k, 0)
            seen[k] = n + 1
            job.copy(occurrence = n)
        }
    }

    /** Returns a job if this object carries a `timer`; `gear_up` and other fields are not timers. */
    private fun JsonObject.toJob(
        category: JobCategory,
        source: JobSource,
        parentDataId: Int? = null,
        typeDataId: Int? = null,
    ): Job? {
        val timer = long("timer") ?: return null
        val id = int("data") ?: return null
        return Job(
            category = category,
            source = source,
            dataId = id,
            currentLevel = int("lvl") ?: 0,
            remainingAtT0 = timer.coerceAtLeast(0),
            helperLinked = flag("helper_recurrent"),
            parentDataId = parentDataId,
            typeDataId = typeDataId,
            isGoblin = flag("extra"),
            helperTimer = (long("helper_timer") ?: 0L).coerceAtLeast(0),
        )
    }

    /** True only for an actual `true` value (`"helper_recurrent": false` is common). */
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>() ?: emptyList()
}
