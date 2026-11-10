package com.aditya.clashtimer.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

private const val HOUR = 3600L

/** Helper session cycle: a new session starts 23 h after the previous one started. */
const val DEFAULT_HELPER_PERIOD_SEC = 23 * HOUR

/**
 * All boost amounts are settings (Supercell may rebalance). Every boost is described as
 * "seconds saved per [boostDurationSec] window", i.e. extra speed = saving / duration:
 *  - Builder potion 9 h saved per hour  → 10×
 *  - Builder Bite   1 h saved per hour  →  2×
 *  - Research/Pet potion 23 h per hour  → 24×
 *  - Helpers: saving = helper level in hours per hour (Apprentice lvl 8 → 9× total speed).
 *    This is the game's "Working speed ×N" convention, which means N hours *saved* per hour.
 */
@Serializable
data class BoostSettings(
    val boostDurationSec: Long = HOUR,
    val builderPotionSavingSec: Long = 9 * HOUR,
    val builderBiteSavingSec: Long = 1 * HOUR,
    val researchPotionSavingSec: Long = 23 * HOUR,
    val petPotionSavingSec: Long = 23 * HOUR,
    /** null = use the Apprentice's level from the export (level hours). */
    val apprenticeSavingSecOverride: Long? = null,
    /** null = use the Lab Assistant's level from the export (level hours). */
    val labAssistantSavingSecOverride: Long? = null,
    val helperWindowSec: Long = HOUR,
    /** Time from one helper session's start to the next: 23 h, not 24 h. */
    val helperPeriodSec: Long = DEFAULT_HELPER_PERIOD_SEC,
    /** Builder potions and snacks also speed up the event Goblin Builder's job (assumed). */
    val boostsApplyToGoblin: Boolean = true,
) {
    fun savingFor(type: BoostType): Long = when (type) {
        BoostType.BUILDER_POTION -> builderPotionSavingSec
        BoostType.BUILDER_BITE -> builderBiteSavingSec
        BoostType.RESEARCH_POTION -> researchPotionSavingSec
        BoostType.PET_POTION -> petPotionSavingSec
    }
}

/** Tap-activated boosts. Each applies to every busy job of its category. */
@Serializable
enum class BoostType(val category: JobCategory) {
    BUILDER_POTION(JobCategory.BUILDER),
    BUILDER_BITE(JobCategory.BUILDER),
    RESEARCH_POTION(JobCategory.LAB),
    PET_POTION(JobCategory.PET),
}

/** One potion/snack window, [start, end). */
@Serializable
data class BoostUse(val type: BoostType, val start: Long, val end: Long)

object BoostStacking {
    /**
     * Adds one boost of [type] tapped at [tapTime]. If a boost of the same type is still running
     * (or queued) at [tapTime], the new hour is appended right after the last one ends.
     * Different types (e.g. Builder potion vs Builder Bite) run in parallel, not back-to-back.
     */
    fun add(existing: List<BoostUse>, type: BoostType, tapTime: Long, durationSec: Long): List<BoostUse> {
        val lastEnd = existing.filter { it.type == type }.maxOfOrNull { it.end }
        val start = if (lastEnd != null && lastEnd > tapTime) lastEnd else tapTime
        return existing + BoostUse(type, start, start + durationSec)
    }

    /**
     * Widget "−": undoes the latest boost of [type] still running or queued at [now].
     * One that hasn't started is removed; one already running is cut off at [now], because the time it
     * did run really sped things up. Nothing changes if no boost of that type is left.
     */
    fun undoLast(existing: List<BoostUse>, type: BoostType, now: Long): List<BoostUse> {
        val last = existing.filter { it.type == type && it.end > now }.maxByOrNull { it.start } ?: return existing
        val rest = existing - last
        return if (last.start >= now) rest else rest + last.copy(end = now)
    }

    /** Undo: removes the most recently queued boost of [type]. */
    fun removeLast(existing: List<BoostUse>, type: BoostType): List<BoostUse> {
        val last = existing.filter { it.type == type }.maxByOrNull { it.start } ?: return existing
        return existing - last
    }
}

/** Which job each helper is linked to, by [Job.key]. Only one link per helper. */
@Serializable
data class HelperLinks(val apprenticeJobKey: String? = null, val labAssistantJobKey: String? = null) {
    companion object {
        /** Links as reported by `helper_recurrent: true` in the export. */
        fun fromExport(export: VillageExport) = HelperLinks(
            apprenticeJobKey = export.builderJobs.firstOrNull { it.helperLinked }?.key,
            labAssistantJobKey = export.labJobs.firstOrNull { it.helperLinked }?.key,
        )
    }
}

/** A source of extra speed over time. Total rate at t = 1 + Σ extraRateAt(t). */
sealed interface SpeedBoost {
    fun extraRateAt(t: Long): Double
    /** Next time strictly after [t] at which this boost's rate changes, or null if never. */
    fun nextEdgeAfter(t: Long): Long?
}

data class FixedWindow(val start: Long, val end: Long, val extraRate: Double) : SpeedBoost {
    override fun extraRateAt(t: Long) = if (t in start until end) extraRate else 0.0
    override fun nextEdgeAfter(t: Long): Long? = when {
        t < start -> start
        t < end -> end
        else -> null
    }
}

/** Windows of [duration] starting at firstStart + k·period, k = 0, 1, 2, … */
data class PeriodicWindow(
    val firstStart: Long,
    val duration: Long,
    val period: Long,
    val extraRate: Double,
) : SpeedBoost {
    init {
        require(period > 0 && duration in 1..period)
    }

    private fun windowStartAtOrBefore(t: Long): Long =
        firstStart + floor((t - firstStart).toDouble() / period).toLong() * period

    override fun extraRateAt(t: Long): Double {
        if (t < firstStart) return 0.0
        return if (t < windowStartAtOrBefore(t) + duration) extraRate else 0.0
    }

    override fun nextEdgeAfter(t: Long): Long {
        if (t < firstStart) return firstStart
        val s = windowStartAtOrBefore(t)
        return if (t < s + duration) s + duration else s + period
    }
}

data class JobTiming(val job: Job, val finishAt: Long)

class TimerEngine(val settings: BoostSettings = BoostSettings()) {

    /** All speed boosts that apply to [job] after the export timestamp. */
    fun boostsFor(job: Job, export: VillageExport, links: HelperLinks, boosts: List<BoostUse>): List<SpeedBoost> {
        val result = mutableListOf<SpeedBoost>()
        val dur = settings.boostDurationSec.toDouble()
        val potionsApply = !job.isGoblin || settings.boostsApplyToGoblin
        boosts.filter { potionsApply && it.type.category == job.category }.forEach { use ->
            result += FixedWindow(use.start, use.end, settings.savingFor(use.type) / dur)
        }
        result += helperWindows(job, export, links)
        return result
    }

    /**
     * The linked helper's sessions for [job]: the one running at export time (`helper_timer`,
     * `[T0, T0 + helper_timer]`), then one hour at `T0 + helper_cooldown + k · helperPeriodSec`.
     */
    private fun helperWindows(job: Job, export: VillageExport, links: HelperLinks): List<SpeedBoost> {
        val (helperId, override) = when {
            job.category == JobCategory.BUILDER && job.key == links.apprenticeJobKey ->
                GameIds.HELPER_BUILDERS_APPRENTICE to settings.apprenticeSavingSecOverride
            job.category == JobCategory.LAB && job.key == links.labAssistantJobKey ->
                GameIds.HELPER_LAB_ASSISTANT to settings.labAssistantSavingSecOverride
            else -> return emptyList()
        }
        val helper = export.helper(helperId) ?: return emptyList()
        val saving = override ?: (helper.level * HOUR)
        if (saving <= 0) return emptyList()
        val rate = saving.toDouble() / settings.helperWindowSec
        return listOfNotNull(
            job.helperTimer.takeIf { it > 0 }?.let { FixedWindow(export.timestamp, export.timestamp + it, rate) },
            PeriodicWindow(
                firstStart = export.timestamp + helper.cooldown,
                duration = settings.helperWindowSec,
                period = settings.helperPeriodSec.coerceAtLeast(settings.helperWindowSec),
                extraRate = rate,
            ),
        )
    }

    fun finishTime(job: Job, export: VillageExport, links: HelperLinks, boosts: List<BoostUse>): Long =
        finishTime(export.timestamp, job.remainingAtT0, boostsFor(job, export, links, boosts))

    fun remainingAt(job: Job, export: VillageExport, links: HelperLinks, boosts: List<BoostUse>, t: Long): Long =
        remainingAt(export.timestamp, job.remainingAtT0, boostsFor(job, export, links, boosts), t)

    fun computeAll(export: VillageExport, links: HelperLinks, boosts: List<BoostUse>): List<JobTiming> =
        export.jobs.map { JobTiming(it, finishTime(it, export, links, boosts)) }

    companion object {
        const val MAX_SEGMENTS = 100_000
        private const val EPS = 1e-6

        /**
         * Steps segment by segment between boost edges, starting at [t0]. Boost time before t0
         * is never counted (it is already reflected in the export's `timer`).
         * Returns the finish time rounded up to the whole second.
         */
        fun finishTime(t0: Long, remaining: Long, boosts: List<SpeedBoost>): Long {
            val end = simulate(t0, remaining, boosts, until = null)
            return ceil(end.time - EPS).toLong()
        }

        /** Raw remaining seconds at time [t] (≥ t0), rounded to the nearest second. */
        fun remainingAt(t0: Long, remaining: Long, boosts: List<SpeedBoost>, t: Long): Long {
            require(t >= t0) { "t must be ≥ t0" }
            val state = simulate(t0, remaining, boosts, until = t)
            return Math.round(state.remaining).coerceAtLeast(0)
        }

        private class State(val time: Double, val remaining: Double)

        private fun simulate(t0: Long, remaining: Long, boosts: List<SpeedBoost>, until: Long?): State {
            var t = t0
            var r = remaining.toDouble()
            repeat(MAX_SEGMENTS) {
                if (r <= EPS) return State(t.toDouble(), 0.0)
                if (until != null && t >= until) return State(t.toDouble(), r)
                val rate = 1.0 + boosts.sumOf { it.extraRateAt(t) }
                val edge = (boosts.mapNotNull { it.nextEdgeAfter(t) } + listOfNotNull(until)).minOrNull()
                val timeToFinish = r / rate
                if (edge == null || t + timeToFinish <= edge) {
                    return State(t + timeToFinish, 0.0)
                }
                r -= (edge - t) * rate
                t = edge
            }
            // Safety net: should be unreachable. Finish the rest at 1×.
            return if (until != null) State(t.toDouble(), r) else State(t + r, 0.0)
        }
    }
}
