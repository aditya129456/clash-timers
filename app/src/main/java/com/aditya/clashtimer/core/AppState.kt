package com.aditya.clashtimer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the widget needs, persisted as one JSON string in DataStore.
 * The export is kept as its raw text and re-parsed on load, so parser improvements in a later
 * app version apply to already-synced data.
 */
@Serializable
data class AppState(
    val exportJson: String? = null,
    val links: HelperLinks = HelperLinks(),
    val boosts: List<BoostUse> = emptyList(),
    /** True after a failed sync, until the next good one. */
    val syncFailed: Boolean = false,
    /**
     * Job key → the job's remaining time when a sync first saw it. Used as the progress bar's full
     * length when the bundled game data has no time for that upgrade.
     */
    val firstRemaining: Map<String, Long> = emptyMap(),
    /** Keys of finished jobs that already got a "… free" notification (reset by every sync). */
    val notified: Set<String> = emptySet(),
) {
    /** The parsed export, or null if nothing has been synced yet. */
    val export: VillageExport? by lazy {
        exportJson?.let { (ExportParser.parse(it) as? ParseResult.Success)?.export }
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        internal val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): AppState =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: AppState()
    }
}

/** User settings (edited on the settings screen; defaults are used until then). */
@Serializable
data class AppSettings(
    /** null = derive from the export (Builder's Huts + B.O.B), falling back to [DEFAULT_TOTAL_BUILDERS]. */
    val totalBuildersOverride: Int? = null,
    val boosts: BoostSettings = BoostSettings(),
    val notificationsEnabled: Boolean = true,
    /**
     * Per-second countdowns drawn by a RemoteViews Chronometer. Off = the static `1d 20h` text,
     * a fallback in case a launcher (HiOS) doesn't render chronometers; toggled on the launcher screen.
     */
    val liveCountdown: Boolean = true,
    /**
     * Name on the widget header. The export has no player name, so the app asks on first launch.
     * null = never asked (the prompt shows); blank = asked and left empty ([DEFAULT_PLAYER_NAME]).
     */
    val playerName: String? = null,
) {
    /** The name the widget shows: [playerName], or [DEFAULT_PLAYER_NAME] if it is missing or blank. */
    val displayName: String get() = playerName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLAYER_NAME

    /** True until the first-launch name prompt has been answered (or dismissed). */
    val needsPlayerName: Boolean get() = playerName == null


    fun totalBuilders(export: VillageExport?): Int {
        val base = totalBuildersOverride ?: export?.derivedTotalBuilders ?: DEFAULT_TOTAL_BUILDERS
        // Never show fewer builders than there are running regular builder jobs (the Goblin is extra).
        return maxOf(base, export?.regularBuilderJobs?.size ?: 0)
    }

    fun encode(): String = AppState.JSON.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_TOTAL_BUILDERS = 6
        const val DEFAULT_PLAYER_NAME = "Chief"
        /** Longest name accepted by the prompt, so the header fits beside the Sync button. */
        const val MAX_PLAYER_NAME = 16

        /** What the prompt stores: trimmed and cut to [MAX_PLAYER_NAME]; empty means "use Chief". */
        fun cleanName(input: String): String = input.trim().take(MAX_PLAYER_NAME).trim()

        fun decode(text: String?): AppSettings =
            text?.let { runCatching { AppState.JSON.decodeFromString(serializer(), it) }.getOrNull() }
                ?: AppSettings()
    }
}

/**
 * Recent sync attempts and breadcrumbs, newest first, shown on the launcher screen because most
 * players can't read logcat on their phone. [ok] is null for breadcrumbs (e.g. "Sync screen opened").
 */
@Serializable
data class SyncLogEntry(val at: Long, val source: String, val ok: Boolean?, val message: String)

@Serializable
data class SyncLog(val entries: List<SyncLogEntry> = emptyList()) {
    fun add(entry: SyncLogEntry): SyncLog = SyncLog((listOf(entry) + entries).take(MAX_ENTRIES))

    /** The latest real result (success or failure), skipping breadcrumbs. */
    val lastResult: SyncLogEntry? get() = entries.firstOrNull { it.ok != null }

    fun encode(): String = AppState.JSON.encodeToString(serializer(), this)

    companion object {
        const val MAX_ENTRIES = 40

        fun decode(text: String?): SyncLog =
            text?.let { runCatching { AppState.JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: SyncLog()
    }
}
