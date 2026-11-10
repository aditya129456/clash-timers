package com.aditya.clashtimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.appwidget.AppWidgetManager
import com.aditya.clashtimer.core.AppSettings
import com.aditya.clashtimer.core.AppState
import com.aditya.clashtimer.core.Countdown
import com.aditya.clashtimer.core.RowButton
import com.aditya.clashtimer.core.RowKind
import com.aditya.clashtimer.core.SyncLog
import com.aditya.clashtimer.core.SyncLogEntry
import com.aditya.clashtimer.core.WidgetModel
import com.aditya.clashtimer.core.WidgetModelBuilder
import com.aditya.clashtimer.core.WidgetRow
import com.aditya.clashtimer.data.Names
import com.aditya.clashtimer.data.StateRepository
import com.aditya.clashtimer.data.Times
import com.aditya.clashtimer.sync.SyncRunner
import com.aditya.clashtimer.ui.AboutSection
import com.aditya.clashtimer.ui.CrashSection
import com.aditya.clashtimer.ui.PlayerNameDialog
import com.aditya.clashtimer.ui.SettingsSection
import com.aditya.clashtimer.ui.SwitchRow
import com.aditya.clashtimer.ui.theme.ClashTimerTheme
import com.aditya.clashtimer.widget.WidgetAlarm
import com.aditya.clashtimer.widget.WidgetIcons
import com.aditya.clashtimer.widget.WidgetRenderer
import com.aditya.clashtimer.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Launcher screen: sync from the clipboard inside the app and show, in plain text, what happened.
 * Players usually have no adb/logcat, so this screen is the diagnostics channel.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClashTimerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SyncScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")
private fun fmt(epochSec: Long): String = TIME.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

@Composable
private fun SyncScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { StateRepository(context) }
    val names = remember { Names.get(context) }
    val times = remember { Times.get(context) }
    val scope = rememberCoroutineScope()

    val state by repo.state.collectAsState(AppState())
    val settings by repo.settings.collectAsState(AppSettings())
    // null until DataStore has loaded, so the first-launch prompt doesn't flash for returning players.
    val storedSettings by repo.settings.collectAsState<AppSettings, AppSettings?>(null)
    val log by repo.syncLog.collectAsState(SyncLog())

    if (storedSettings?.needsPlayerName == true) {
        // First launch: ask for the widget's name. Skip / empty = "Chief"; stored as "" so it isn't asked again.
        val answer = { name: String ->
            scope.launch {
                repo.updateSettings { it.copy(playerName = name) }
                SyncRunner.refreshWidgets(context, "app")
            }
            Unit
        }
        PlayerNameDialog(current = null, firstLaunch = true, onDone = answer, onDismiss = { answer("") })
    }
    var busy by remember { mutableStateOf(false) }
    var widgetCount by remember { mutableIntStateOf(-1) }
    var widgetSizes by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            widgetCount = runCatching { WidgetUpdater.widgetIds(context).size }.getOrDefault(-1)
            widgetSizes = runCatching { widgetSizeReport(context, state) }.getOrDefault("")
            delay(1_000)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Clash Timers", style = MaterialTheme.typography.headlineSmall)
        Text("1. In Clash: Settings > Data Export > Copy.\n2. Come back here and tap the button.")

        Button(
            onClick = {
                busy = true
                val text = SyncRunner.readClipboard(context) // read now, while this screen has focus
                scope.launch {
                    SyncRunner.sync(context, text, "app")
                    now = System.currentTimeMillis() / 1000
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Syncing…" else "Sync from clipboard") }

        // Last result, in plain text.
        val last = log.lastResult
        Text("Last sync result", fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                if (last == null) "No sync yet."
                else "${if (last.ok == true) "OK" else "FAILED"} · ${fmt(last.at)} · from ${last.source}\n${SyncRunner.plainText(last.message)}",
                color = if (last?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        state.export?.let { Text("Export copied at ${fmt(it.timestamp)} (village ${it.tag})") }

        HorizontalDivider()
        SettingsSection(settings, state.export, now) { change ->
            scope.launch {
                repo.updateSettings(change)
                SyncRunner.refreshWidgets(context, "app")
            }
        }

        // ---- Diagnostics (no logcat on most phones): below the settings. ----
        HorizontalDivider()
        Text("Diagnostics", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        CrashSection()
        Text("Widget", fontWeight = FontWeight.Bold)
        Text(
            when (widgetCount) {
                -1 -> "Widgets placed: unknown"
                0 -> "No Clash Timers widget is on the home screen yet."
                else -> "Widgets placed: $widgetCount"
            },
        )
        if (widgetSizes.isNotEmpty()) Text(widgetSizes, style = MaterialTheme.typography.bodySmall)
        Text(
            "Icons in assets/icons: ${WidgetIcons.get(context).available.size} · upgrade times for ${times.size} items · " +
                "exact alarms: ${if (WidgetAlarm.canScheduleExact(context)) "allowed" else "not allowed (1-min window)"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!WidgetAlarm.canScheduleExact(context) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(android.net.Uri.parse("package:${context.packageName}")),
                    )
                }
            }) { Text("Allow exact alarms") }
        }
        SwitchRow("Live seconds countdown (turn off if the times don't show)", settings.liveCountdown) { on ->
            scope.launch {
                repo.updateSettings { it.copy(liveCountdown = on) }
                SyncRunner.refreshWidgets(context, "app")
            }
        }
        OutlinedButton(
            onClick = { scope.launch { widgetCount = SyncRunner.refreshWidgets(context, "app") } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh widget") }

        Text("The widget should show:", fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                widgetPreview(WidgetModelBuilder.build(state, settings, names, times, now)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text("Recent activity", fontWeight = FontWeight.Bold)
        if (log.entries.isEmpty()) Text("Nothing yet.")
        SelectionContainer {
            Text(SyncRunner.plainText(log.entries.joinToString("\n") { it.line() }), style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        AboutSection()
        Spacer(Modifier.height(24.dp))
    }
}

private fun SyncLogEntry.line(): String {
    val mark = when (ok) { true -> "OK"; false -> "FAIL"; null -> "·" }
    return "${fmt(at)}  [$source] $mark  $message"
}

/** Each placed widget's size as the launcher reports it, and which row size it gets. */
private fun widgetSizeReport(context: android.content.Context, state: AppState): String {
    val manager = AppWidgetManager.getInstance(context)
    val rows = (state.export?.jobs?.size ?: 0) + 2
    return WidgetUpdater.widgetIds(context).joinToString("\n") { id ->
        val o = manager.getAppWidgetOptions(id)
        val w = WidgetRenderer.reportedWidthDp(o)
        val h = WidgetRenderer.reportedHeightDp(o)
        val size = if (w != null && h != null) "${w}×${h} dp" else "size not reported"
        val rowSize = if (WidgetRenderer.useCompactRows(o, rows)) "compact 25 dp rows" else "30 dp rows"
        "Widget #$id: $size · $rowSize (30 dp rows need about ${WidgetRenderer.normalHeightDp(rows)} dp)"
    }
}

/** The widget model as text, so a rendering problem can be told apart from a data problem. */
private fun widgetPreview(m: WidgetModel): String = SyncRunner.plainText(buildString {
    appendLine("${m.title}   [Sync]")
    if (!m.hasData) {
        append(if (m.syncFailed) WidgetModelBuilder.SYNC_FAILED else WidgetModelBuilder.NO_DATA)
        return@buildString
    }
    fun line(r: WidgetRow) {
        val time = if (r.kind == RowKind.RUNNING) Countdown.text(r.remaining) else r.staticStatus
        val button = when (r.button) {
            RowButton.NONE -> ""
            RowButton.LINK -> " [+]"
            RowButton.APPRENTICE -> " [Apprentice]"
            RowButton.LAB_ASSISTANT -> " [Assistant]"
        }
        val goblin = if (r.goblin) "GOBLIN " else ""
        val label = if (r.kind == RowKind.RUNNING) r.label else r.name // finished rows: name only
        appendLine("  $goblin$label | $time | ${(r.progress * 100).toInt()}%$button")
    }
    appendLine("${m.buildersLabel}${if (m.goblinWorking) " + Goblin" else ""}   [${m.bite.text}] [${m.builderPotion.text}]")
    m.builderRows.forEach(::line)
    appendLine("LAB   [${m.researchPotion.text}]")
    m.labRows.forEach(::line)
    appendLine("PETS   [${m.petPotion.text}]")
    line(m.petRow)
    append(m.footerLeft.joinToString("") { it.text }).append("   ").append(m.footerRight)
})
