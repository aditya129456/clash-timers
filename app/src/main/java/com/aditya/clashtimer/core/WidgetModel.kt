package com.aditya.clashtimer.core

import kotlin.math.ceil

/**
 * When a job counts as finished. The widget rows, the notifications and the alarms all use this.
 *
 * A job with [GRACE_SEC] seconds or less left is already finished. So a redraw that lands just
 * before the finish (an alarm delivered early, or `now` truncated to whole seconds) draws
 * "Completed ✓", not a chronometer that would tick past zero into negative numbers.
 */
object Finish {
    const val GRACE_SEC = 2L

    fun isDone(finishAt: Long, now: Long): Boolean = finishAt - now <= GRACE_SEC
}

/**
 * Everything the widget shows. Built without Android so it
 * can be unit tested; the RemoteViews renderer only maps it onto views.
 */
data class WidgetModel(
    val hasData: Boolean,
    val playerName: String,
    val townHall: Int?,
    /** Running regular builder jobs (the Goblin is not counted). */
    val buildersBusy: Int,
    val buildersTotal: Int,
    /** The event Goblin Builder's job is still running: header shows `+ Goblin`. */
    val goblinWorking: Boolean,
    val bite: BoostPill,
    val builderPotion: BoostPill,
    val researchPotion: BoostPill,
    val petPotion: BoostPill,
    /** One row per builder job (finished ones say Completed); free builders get no rows. */
    val builderRows: List<WidgetRow>,
    val labRows: List<WidgetRow>,
    val petRow: WidgetRow,
    /** Helper status (or the sync-failed message), left side of the footer. */
    val footerLeft: List<FooterPart>,
    /** `synced 14m ago`, right side of the footer. */
    val footerRight: String,
    val syncFailed: Boolean,
    /**
     * Next time (epoch s) something other than a countdown's own ticking changes: a job finishing,
     * a potion/helper window edge (progress bars), a pill's minutes, the footer's sync age.
     */
    val nextChangeAt: Long?,
) {
    val buildersLabel: String get() = "BUILDERS $buildersBusy/$buildersTotal"
    val title: String get() = townHall?.let { "$playerName TH$it" } ?: playerName
    val allRows: List<WidgetRow> get() = builderRows + labRows + petRow
    val labRow: WidgetRow get() = labRows.firstOrNull() ?: WidgetModelBuilder.idleRow("Laboratory", WidgetModelBuilder.LABORATORY)
}

/** A potion/snack button on a section line: `Bite` / `+1` when idle, minutes left when running. */
data class BoostPill(
    val type: BoostType,
    val secondsLeft: Long?,
    /** A boost of this type is running or queued, so the small `−` (undo) is shown. */
    val canUndo: Boolean = secondsLeft != null,
) {
    val active: Boolean get() = secondsLeft != null
    val text: String
        get() = secondsLeft?.let(WidgetModelBuilder::formatPill)
            ?: if (type == BoostType.BUILDER_BITE) "Bite" else "+1"
}

enum class FooterTone { NORMAL, IDLE, ERROR }

data class FooterPart(val text: String, val tone: FooterTone = FooterTone.NORMAL)

enum class RowKind { RUNNING, COMPLETED, IDLE }

/** The small button at the right of a row. */
enum class RowButton {
    /** Space kept, nothing drawn (pets, finished rows). */
    NONE,
    /** Green `+`: link the helper here. */
    LINK,
    /** Gold badge: the Builder's Apprentice works on this job. */
    APPRENTICE,
    /** Gold badge: the Lab Assistant works on this job. */
    LAB_ASSISTANT,
}

data class WidgetRow(
    val kind: RowKind,
    /** `Inferno Tower`, `Cake-A-Pult Damage`, `Laboratory`. */
    val name: String,
    /** Shown after the arrow, e.g. `12`; `(new)` without an arrow for a building under construction. */
    val levelText: String?,
    val showArrow: Boolean,
    /** Icon files to try in order: `assets/icons/<id>.webp` (or `.png`). */
    val iconIds: List<Int>,
    /** Placeholder when no icon file exists, e.g. `IT`. */
    val initials: String,
    val finishAt: Long?,
    /** Wall-clock seconds until [finishAt] at build time. */
    val remaining: Long,
    /** 0..1, how much of the full upgrade is done. */
    val progress: Float,
    val button: RowButton,
    val goblin: Boolean = false,
    val jobKey: String? = null,
) {
    /** `Inferno Tower → 12`, `Super Wizard Tower (new)`. */
    val label: String
        get() = when {
            levelText == null -> name
            showArrow -> "$name → $levelText"
            else -> "$name $levelText"
        }

    /** Text when the per-second countdown is off (or for previews): `1d 20h`, `Completed`, `Idle`. */
    val staticStatus: String
        get() = when (kind) {
            RowKind.RUNNING -> WidgetModelBuilder.formatRemaining(remaining)
            RowKind.COMPLETED -> WidgetModelBuilder.COMPLETED
            RowKind.IDLE -> WidgetModelBuilder.IDLE
        }
}

object WidgetModelBuilder {
    const val COMPLETED = "Completed"
    const val IDLE = "Idle"
    const val SYNC_FAILED = "Sync failed — copy data and tap Sync"
    const val NO_DATA = "Copy village data in Clash, then tap Sync"

    fun build(
        state: AppState,
        settings: AppSettings,
        names: NameMap,
        times: UpgradeTimes,
        now: Long,
    ): WidgetModel {
        val export = state.export
        val pills = { type: BoostType ->
            BoostPill(type, boostLeft(state.boosts, type, now), state.boosts.any { it.type == type && it.end > now })
        }
        if (export == null) {
            return WidgetModel(
                hasData = false, playerName = settings.displayName, townHall = null,
                buildersBusy = 0, buildersTotal = settings.totalBuilders(null), goblinWorking = false,
                bite = pills(BoostType.BUILDER_BITE), builderPotion = pills(BoostType.BUILDER_POTION),
                researchPotion = pills(BoostType.RESEARCH_POTION), petPotion = pills(BoostType.PET_POTION),
                builderRows = emptyList(),
                labRows = listOf(idleRow("Laboratory", LABORATORY)),
                petRow = idleRow("Pet House", PET_HOUSE),
                footerLeft = if (state.syncFailed) listOf(FooterPart(SYNC_FAILED, FooterTone.ERROR)) else emptyList(),
                footerRight = "", syncFailed = state.syncFailed, nextChangeAt = null,
            )
        }

        val engine = TimerEngine(settings.boosts)
        val at = maxOf(now, export.timestamp) // the engine starts at T0
        val finish = export.jobs.associate { it.key to engine.finishTime(it, export, state.links, state.boosts) }
        fun running(job: Job) = !Finish.isDone(finish.getValue(job.key), now)

        fun row(job: Job): WidgetRow {
            val finishAt = finish.getValue(job.key)
            val done = Finish.isDone(finishAt, now)
            val workLeft = if (done) 0L else engine.remainingAt(job, export, state.links, state.boosts, at)
            val full = times.fullTime(job)?.takeIf { it >= job.remainingAtT0 }
                ?: maxOf(state.firstRemaining[job.key] ?: 0L, job.remainingAtT0)
            val title = names.title(job)
            val button = when {
                done -> RowButton.NONE
                job.key == state.links.apprenticeJobKey -> RowButton.APPRENTICE
                job.key == state.links.labAssistantJobKey -> RowButton.LAB_ASSISTANT
                job.category == JobCategory.PET -> RowButton.NONE
                else -> RowButton.LINK
            }
            return WidgetRow(
                kind = if (done) RowKind.COMPLETED else RowKind.RUNNING,
                name = title,
                levelText = if (job.isNewBuilding) "(new)" else job.targetLevel.toString(),
                showArrow = !job.isNewBuilding,
                iconIds = listOfNotNull(job.dataId, job.typeDataId, job.parentDataId).distinct(),
                initials = initials(title),
                finishAt = finishAt,
                remaining = if (done) 0 else finishAt - now,
                progress = if (done || full <= 0) 1f else (1.0 - workLeft.toDouble() / full).coerceIn(0.0, 1.0).toFloat(),
                button = button,
                goblin = job.isGoblin,
                jobKey = job.key,
            )
        }

        fun sorted(jobs: List<Job>) = jobs.sortedBy { finish.getValue(it.key) }

        // Free builders get no rows; finished jobs stay as "Completed" until the next sync.
        // The Goblin's row disappears once it is done (it is not one of the player's builders).
        val builderRows = sorted(export.builderJobs).filter { !it.isGoblin || running(it) }.map(::row)
        val labRows = sorted(export.labJobs).filter { !it.isGoblin || running(it) }.map(::row)
            .ifEmpty { listOf(idleRow("Laboratory", LABORATORY)) }
        val petRow = sorted(export.petJobs).firstOrNull()?.let(::row) ?: idleRow("Pet House", PET_HOUSE)

        val footerLeft = if (state.syncFailed) {
            listOf(FooterPart(SYNC_FAILED, FooterTone.ERROR))
        } else {
            helperStatus(
                apprentice = runningJob(state.links.apprenticeJobKey, export, ::running)?.let { shortName(it, names) },
                assistant = runningJob(state.links.labAssistantJobKey, export, ::running)?.let { shortName(it, names) },
            )
        }

        // Edges after which the model itself changes (countdown text edges are added by WidgetSchedule).
        val edges = buildList {
            // A row turns Completed GRACE_SEC before its finish; redraw again GRACE_SEC after it as a safety net.
            finish.values.forEach { add(it - Finish.GRACE_SEC); add(it + Finish.GRACE_SEC) }
            export.jobs.filter(::running).forEach { job ->
                engine.boostsFor(job, export, state.links, state.boosts).mapNotNull { it.nextEdgeAfter(at) }.forEach(::add)
            }
            state.boosts.filter { it.end > now }.forEach { add(if (it.start > now) it.start else it.end) }
            BoostType.entries.forEach { type -> boostLeft(state.boosts, type, now)?.let { add(now + pillEdgeIn(it)) } }
            add(export.timestamp + agoEdge(now - export.timestamp))
        }

        return WidgetModel(
            hasData = true,
            playerName = settings.displayName,
            townHall = export.townHallLevel,
            buildersBusy = export.regularBuilderJobs.count(::running),
            buildersTotal = settings.totalBuilders(export),
            goblinWorking = export.builderJobs.any { it.isGoblin && running(it) },
            bite = pills(BoostType.BUILDER_BITE),
            builderPotion = pills(BoostType.BUILDER_POTION),
            researchPotion = pills(BoostType.RESEARCH_POTION),
            petPotion = pills(BoostType.PET_POTION),
            builderRows = builderRows,
            labRows = labRows,
            petRow = petRow,
            footerLeft = footerLeft,
            footerRight = "synced ${formatAgo(now - export.timestamp)}",
            syncFailed = state.syncFailed,
            nextChangeAt = edges.filter { it > now }.minOrNull(),
        )
    }

    const val LABORATORY = 1000007
    const val PET_HOUSE = 1000068

    internal fun idleRow(name: String, iconId: Int) = WidgetRow(
        kind = RowKind.IDLE, name = name, levelText = null, showArrow = false, iconIds = listOf(iconId),
        initials = initials(name), finishAt = null, remaining = 0, progress = 1f, button = RowButton.NONE,
    )

    private fun runningJob(key: String?, export: VillageExport, running: (Job) -> Boolean): Job? =
        key?.let { k -> export.jobs.firstOrNull { it.key == k } }?.takeIf(running)

    /** Heroes by their title (`King`, `Champion`), everything else by its full name. */
    private fun shortName(job: Job, names: NameMap): String {
        val title = names.title(job)
        return if (job.source == JobSource.HERO) title.substringAfterLast(' ') else title
    }

    /**
     * `Apprentice idle · Assistant idle`, `Apprentice idle · Assistant: Baby Dragon`,
     * `Helpers: King · Baby Dragon`. Idle parts are drawn in orange.
     */
    fun helperStatus(apprentice: String?, assistant: String?): List<FooterPart> =
        if (apprentice != null && assistant != null) {
            listOf(FooterPart("Helpers: $apprentice · $assistant"))
        } else {
            listOf(
                apprentice?.let { FooterPart("Apprentice: $it") } ?: FooterPart("Apprentice idle", FooterTone.IDLE),
                FooterPart(" · "),
                assistant?.let { FooterPart("Assistant: $it") } ?: FooterPart("Assistant idle", FooterTone.IDLE),
            )
        }

    /** `IT` for Inferno Tower, `S` for Scattershot (first letters of the first two words). */
    fun initials(name: String): String =
        name.split(' ').mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
            .take(2).joinToString("").uppercase()

    /**
     * Seconds left on the running chain of [type] boosts at [now] (back-to-back windows count as one
     * run up to the end of the last one), or null if none is running.
     */
    fun boostLeft(boosts: List<BoostUse>, type: BoostType, now: Long): Long? {
        val windows = boosts.filter { it.type == type }.sortedBy { it.start }
        val current = windows.firstOrNull { now >= it.start && now < it.end } ?: return null
        var end = current.end
        for (w in windows) if (w.start <= end && w.end > end) end = w.end
        return end - now
    }

    /** Pill text: `42m`, `1h 50m`, `2h`. Minutes are rounded up so a running boost never shows `0m`. */
    fun formatPill(seconds: Long): String {
        val minutes = ceil(seconds / 60.0).toLong().coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Seconds until [formatPill] of a boost with [left] seconds changes. */
    private fun pillEdgeIn(left: Long): Long = left - 60 * ((left - 1) / 60)

    /** Seconds after T0 when [formatAgo] next changes, given [elapsed] seconds since T0. */
    private fun agoEdge(elapsed: Long): Long {
        val s = elapsed.coerceAtLeast(0)
        val unit = when {
            s < 3600 -> 60L
            s < 2 * 86400 -> 3600L
            else -> 86400L
        }
        return (s / unit + 1) * unit
    }

    /** `1d 20h`, `5h 12m`, `12m`. Minutes are rounded up so a running job never shows `0m`. */
    fun formatRemaining(seconds: Long): String {
        val minutes = (seconds + 59) / 60
        val d = minutes / (24 * 60)
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** `just now`, `12m ago`, `4h ago`, `3d ago`. */
    fun formatAgo(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 2 * 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }
}

/** When the widget must be re-rendered next. */
object WidgetSchedule {
    /**
     * Epoch ms of the next re-render: the model's own next change, or the next countdown format edge
     * ([Countdown.segment]) with [liveCountdown], or the next change of the `1d 20h` text without it.
     */
    fun nextUpdateMs(model: WidgetModel, nowMs: Long, liveCountdown: Boolean): Long? {
        val candidates = buildList {
            model.nextChangeAt?.let { add(it * 1000) }
            model.allRows.filter { it.kind == RowKind.RUNNING && it.finishAt != null }.forEach { row ->
                val finishAt = row.finishAt!!
                // The row turns Completed GRACE_SEC before the finish, so the chronometer never reaches zero.
                add((finishAt - Finish.GRACE_SEC) * 1000)
                if (liveCountdown) {
                    Countdown.segment(finishAt, nowMs)?.let { add(it.nextEdgeMs) }
                } else {
                    staticTextEdge(finishAt, nowMs / 1000)?.let { add(it * 1000) }
                }
            }
        }
        return candidates.filter { it > nowMs }.minOrNull()
    }

    /** Epoch s when [WidgetModelBuilder.formatRemaining] of a job finishing at [finishAt] next changes. */
    fun staticTextEdge(finishAt: Long, now: Long): Long? {
        val left = finishAt - now
        if (left <= 0) return null
        val minutes = (left + 59) / 60
        // Under a day the text shows minutes; from a day up it only shows whole hours.
        val nextMinutes = if (minutes > 24 * 60) 60 * (minutes / 60) - 1 else minutes - 1
        val edge = finishAt - nextMinutes * 60
        return if (edge > now) edge else null
    }
}
