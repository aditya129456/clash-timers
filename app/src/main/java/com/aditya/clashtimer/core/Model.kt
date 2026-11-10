package com.aditya.clashtimer.core

enum class JobCategory { BUILDER, LAB, PET }

/** Where in the export a job was found. */
enum class JobSource { BUILDING, MODULE, TRAP, HERO, GUARDIAN, UNIT, SPELL, SIEGE_MACHINE, PET }

/**
 * One running upgrade from the export.
 *
 * @param currentLevel `lvl` from the export; 0 means the building is under construction.
 * @param remainingAtT0 the export's `timer`: raw seconds remaining at the export timestamp
 *   (never includes future helper sessions).
 * @param helperLinked `helper_recurrent: true` on the item.
 * @param parentDataId for crafted-defense modules: the parent defense's `data` (e.g. 1000097).
 * @param typeDataId for crafted-defense modules: the `types[].data` the module belongs to.
 * @param isGoblin `"extra": true` — done by the event Goblin Builder (a temporary extra builder),
 *   not one of the regular builders.
 * @param helperTimer `helper_timer`: seconds left in the helper hour running on this item right now
 *   (0 = no session running).
 * @param occurrence 0-based index among jobs with the same category + data + target level,
 *   so two identical upgrades (e.g. two Wizard Towers → 17) get distinct keys.
 */
data class Job(
    val category: JobCategory,
    val source: JobSource,
    val dataId: Int,
    val currentLevel: Int,
    val remainingAtT0: Long,
    val helperLinked: Boolean,
    val parentDataId: Int? = null,
    val typeDataId: Int? = null,
    val occurrence: Int = 0,
    val isGoblin: Boolean = false,
    val helperTimer: Long = 0,
) {
    val targetLevel: Int get() = currentLevel + 1
    val isNewBuilding: Boolean get() = currentLevel == 0
    val key: String get() = "$category:$dataId:$targetLevel#$occurrence"
}

data class Helper(val dataId: Int, val level: Int, val cooldown: Long)

data class VillageExport(
    val tag: String,
    /** UNIX seconds when the export was copied — T0 for the timer engine. */
    val timestamp: Long,
    val townHallLevel: Int?,
    /** Sum of `cnt` over Builder's Hut entries (1000015). */
    val builderHutCount: Int,
    /** B.O.B's Hut (1000064) present → one extra builder. */
    val hasBobsHut: Boolean = false,
    val helpers: List<Helper>,
    val jobs: List<Job>,
    /**
     * Categories whose helper has `helper_recurrent: true` on an item with no `timer`, i.e. the helper
     * is assigned to something already finished and is effectively idle (e.g. a spell in export 3).
     */
    val helperOnFinishedItem: Set<JobCategory> = emptySet(),
    /** `boosts.builder_boost`: seconds left on an active home-village builder potion at T0, or null. */
    val builderBoostRemaining: Long? = null,
) {
    /** Builders derived from the export: huts + B.O.B. Null if no huts were found. */
    val derivedTotalBuilders: Int?
        get() = if (builderHutCount > 0) builderHutCount + (if (hasBobsHut) 1 else 0) else null

    val builderJobs: List<Job> get() = jobs.filter { it.category == JobCategory.BUILDER }
    /** Builder jobs done by the regular builders (excludes the Goblin Builder). */
    val regularBuilderJobs: List<Job> get() = builderJobs.filter { !it.isGoblin }
    val labJobs: List<Job> get() = jobs.filter { it.category == JobCategory.LAB }
    val petJobs: List<Job> get() = jobs.filter { it.category == JobCategory.PET }

    /** The spec says at most one; extras (shouldn't happen) are still kept in [jobs]. */
    val labJob: Job? get() = labJobs.firstOrNull()
    val petJob: Job? get() = petJobs.firstOrNull()

    fun helper(dataId: Int): Helper? = helpers.firstOrNull { it.dataId == dataId }

    /** All helpers share the same cooldown; null if the export has no helpers. */
    val helperCooldown: Long? get() = helpers.firstOrNull()?.cooldown

    /** First helper activation after T0. Repeats every 86400 s. */
    val nextHelperActivation: Long? get() = helperCooldown?.let { timestamp + it }
}

sealed interface ParseResult {
    data class Success(val export: VillageExport) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}
