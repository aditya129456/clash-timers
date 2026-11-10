package com.aditya.clashtimer.core

/** What a widget button does. Pure state changes; the receiver saves and redraws. */
object WidgetActions {

    /** Tap action names, also used in the tap intents' URIs. */
    const val HELPER = "helper"
    const val ADD_BOOST = "add"
    const val UNDO_BOOST = "undo"

    /**
     * Green `+` / gold `H` on a row. `+` links the category's helper to [jobKey], moving it off any
     * other row (one link per helper); tapping the row the helper is on unlinks it. Pets have no helper.
     */
    fun toggleHelper(state: AppState, jobKey: String): AppState {
        val job = state.export?.jobs?.firstOrNull { it.key == jobKey } ?: return state
        val links = state.links
        val newLinks = when (job.category) {
            JobCategory.BUILDER ->
                links.copy(apprenticeJobKey = if (links.apprenticeJobKey == jobKey) null else jobKey)
            JobCategory.LAB ->
                links.copy(labAssistantJobKey = if (links.labAssistantJobKey == jobKey) null else jobKey)
            JobCategory.PET -> return state
        }
        return state.copy(links = newLinks)
    }

    /** Potion / Bite button: one more hour of [type] from [now], or right after the running one. */
    fun addBoost(state: AppState, type: BoostType, now: Long, settings: BoostSettings): AppState {
        if (state.export == null) return state
        // Windows that ended before the export are already in its timers; drop them while we're here.
        val kept = state.boosts.filter { it.end > (state.export?.timestamp ?: 0) }
        return state.copy(boosts = BoostStacking.add(kept, type, now, settings.boostDurationSec))
    }

    /** Small `−` next to a running boost: undo the last hour of [type]. */
    fun undoBoost(state: AppState, type: BoostType, now: Long): AppState =
        state.copy(boosts = BoostStacking.undoLast(state.boosts, type, now))

    /** Dispatches a tap by its action name and argument (job key or [BoostType] name); unknown taps change nothing. */
    fun apply(state: AppState, action: String, arg: String, now: Long, settings: BoostSettings): AppState {
        val type = BoostType.entries.firstOrNull { it.name == arg }
        return when (action) {
            HELPER -> toggleHelper(state, arg)
            ADD_BOOST -> type?.let { addBoost(state, it, now, settings) } ?: state
            UNDO_BOOST -> type?.let { undoBoost(state, it, now) } ?: state
            else -> state
        }
    }

    /** One line for the on-screen log, e.g. `Apprentice → Logger → 5` or `Builder potion +1 h (until 23:44)`. */
    fun describe(before: AppState, after: AppState, action: String, arg: String, names: NameMap): String {
        fun jobName(key: String?) = key?.let { k -> after.export?.jobs?.firstOrNull { it.key == k } }?.let(names::label)
        return when (action) {
            HELPER -> {
                val (helper, old, new) = if (after.links.apprenticeJobKey != before.links.apprenticeJobKey) {
                    Triple("Apprentice", before.links.apprenticeJobKey, after.links.apprenticeJobKey)
                } else {
                    Triple("Lab Assistant", before.links.labAssistantJobKey, after.links.labAssistantJobKey)
                }
                when {
                    old == new -> "Helper tap changed nothing"
                    new == null -> "$helper unlinked from ${jobName(old) ?: "?"}"
                    else -> "$helper linked to ${jobName(new)}" + (jobName(old)?.let { " (moved from $it)" } ?: "")
                }
            }
            ADD_BOOST -> "${boostName(arg)} +1 h" + if (after.boosts.size == before.boosts.size) " (ignored)" else ""
            UNDO_BOOST -> "${boostName(arg)} undo" + if (after.boosts == before.boosts) " (nothing to undo)" else ""
            else -> "Unknown tap $action/$arg"
        }
    }

    private fun boostName(arg: String) = when (arg) {
        BoostType.BUILDER_POTION.name -> "Builder potion"
        BoostType.BUILDER_BITE.name -> "Builder Bite"
        BoostType.RESEARCH_POTION.name -> "Research potion"
        BoostType.PET_POTION.name -> "Pet potion"
        else -> arg
    }
}

/** A "something finished" notification. */
data class FinishNotice(val jobKey: String, val title: String, val text: String)

object FinishNotices {
    /** Jobs that finished longer ago than this are marked as notified without a notification. */
    const val MAX_AGE_SEC = 12 * 3600L

    data class Result(val notices: List<FinishNotice>, val notified: Set<String>)

    /**
     * Jobs finished at [now] that haven't been announced yet, and the new [AppState.notified] set
     * (only keys that are still finished, so a job that is "un-finished" by an undo can be announced again).
     */
    fun check(state: AppState, settings: AppSettings, names: NameMap, now: Long): Result {
        val export = state.export ?: return Result(emptyList(), emptySet())
        val engine = TimerEngine(settings.boosts)
        val finished = export.jobs
            .map { it to engine.finishTime(it, export, state.links, state.boosts) }
            .filter { (_, at) -> Finish.isDone(at, now) }
        val notices = finished
            .filter { (job, at) -> job.key !in state.notified && at > now - MAX_AGE_SEC }
            .sortedBy { it.second }
            .map { (job, _) -> notice(job, names) }
        return Result(notices, finished.map { it.first.key }.toSet())
    }

    /** Next finish time (epoch s) of a job that isn't [Finish.isDone] at [now]. */
    fun nextFinish(state: AppState, settings: AppSettings, now: Long): Long? {
        val export = state.export ?: return null
        val engine = TimerEngine(settings.boosts)
        return export.jobs.map { engine.finishTime(it, export, state.links, state.boosts) }
            .filter { !Finish.isDone(it, now) }.minOrNull()
    }

    /**
     * Epoch s for the wakeup finish alarm: [Finish.GRACE_SEC] after the next finish, so the redraw it
     * triggers is certain to see the job as done.
     */
    fun alarmAt(state: AppState, settings: AppSettings, now: Long): Long? =
        nextFinish(state, settings, now)?.plus(Finish.GRACE_SEC)

    fun notice(job: Job, names: NameMap): FinishNotice {
        val title = names.title(job)
        val text = if (job.isNewBuilding) "$title is built." else "$title reached level ${job.targetLevel}."
        val heading = when {
            job.isGoblin && job.category == JobCategory.LAB -> "Goblin Researcher done: $title"
            job.category == JobCategory.LAB -> "Lab free"
            job.category == JobCategory.PET -> "Pet house free"
            job.isGoblin -> "Goblin Builder done: $title"
            else -> "Builder free: $title"
        }
        return FinishNotice(job.key, heading, text)
    }
}
