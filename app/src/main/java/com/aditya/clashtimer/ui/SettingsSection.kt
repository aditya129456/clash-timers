package com.aditya.clashtimer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aditya.clashtimer.core.AppSettings
import com.aditya.clashtimer.core.BoostSettings
import com.aditya.clashtimer.core.GameIds
import com.aditya.clashtimer.core.VillageExport
import com.aditya.clashtimer.notify.Notifier

private const val H = 3600L

/**
 * Settings on the launcher screen: boost amounts, helper interval,
 * total builders, the Goblin potion toggle and notifications. Every change is saved at once and
 * the widget is redrawn by [onChange]'s caller.
 */
@Composable
fun SettingsSection(
    settings: AppSettings,
    export: VillageExport?,
    /** The screen's 1-second clock, so the notification permission state is re-read after a trip to settings. */
    now: Long,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val context = LocalContext.current
    val b = settings.boosts
    fun boosts(f: (BoostSettings) -> BoostSettings) = onChange { it.copy(boosts = f(it.boosts)) }

    // Re-read notification permission after the system dialog or a trip to the settings app.
    var permissionTick by remember { mutableIntStateOf(0) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val notificationsAllowed = remember(now, permissionTick) { Notifier.allowed(context) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

        var editName by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Player name")
                Text(settings.displayName, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { editName = true }) { Text("Change") }
        }
        if (editName) {
            PlayerNameDialog(
                current = settings.playerName,
                firstLaunch = false,
                onDone = { name -> onChange { it.copy(playerName = name) }; editName = false },
                onDismiss = { editName = false },
            )
        }

        Text("Potions and snacks: hours saved per hour of use", style = MaterialTheme.typography.labelLarge)
        HoursStepper("Builder potion", b.builderPotionSavingSec, 1..47) { v -> boosts { it.copy(builderPotionSavingSec = v) } }
        HoursStepper("Builder Bite", b.builderBiteSavingSec, 1..23) { v -> boosts { it.copy(builderBiteSavingSec = v) } }
        HoursStepper("Research potion", b.researchPotionSavingSec, 1..47) { v -> boosts { it.copy(researchPotionSavingSec = v) } }
        HoursStepper("Pet potion", b.petPotionSavingSec, 1..47) { v -> boosts { it.copy(petPotionSavingSec = v) } }

        Text("Helpers", style = MaterialTheme.typography.labelLarge)
        AutoHoursStepper(
            "Builder's Apprentice saves per session",
            b.apprenticeSavingSecOverride,
            auto = export?.helper(GameIds.HELPER_BUILDERS_APPRENTICE)?.level?.times(H),
            range = 1..24,
        ) { v -> boosts { it.copy(apprenticeSavingSecOverride = v) } }
        AutoHoursStepper(
            "Lab Assistant saves per session",
            b.labAssistantSavingSecOverride,
            auto = export?.helper(GameIds.HELPER_LAB_ASSISTANT)?.level?.times(H),
            range = 1..24,
        ) { v -> boosts { it.copy(labAssistantSavingSecOverride = v) } }
        Stepper(
            label = "New helper session every",
            value = "${b.helperPeriodSec / H} h",
            hint = "23 h, as measured in game",
            onMinus = (b.helperPeriodSec - H).takeIf { it >= 2 * H }?.let { v -> { boosts { it.copy(helperPeriodSec = v) } } },
            onPlus = (b.helperPeriodSec + H).takeIf { it <= 48 * H }?.let { v -> { boosts { it.copy(helperPeriodSec = v) } } },
        )

        Text("Builders", style = MaterialTheme.typography.labelLarge)
        val derived = export?.derivedTotalBuilders
        val total = settings.totalBuildersOverride
        Stepper(
            label = "Total builders",
            value = total?.toString() ?: "Auto (${derived ?: AppSettings.DEFAULT_TOTAL_BUILDERS})",
            hint = if (total == null) "From Builder's Huts + B.O.B's Hut in the export" else null,
            onMinus = ((total ?: derived ?: AppSettings.DEFAULT_TOTAL_BUILDERS) - 1).takeIf { it >= 1 }
                ?.let { v -> { onChange { it.copy(totalBuildersOverride = v) } } },
            onPlus = ((total ?: derived ?: AppSettings.DEFAULT_TOTAL_BUILDERS) + 1).takeIf { it <= 10 }
                ?.let { v -> { onChange { it.copy(totalBuildersOverride = v) } } },
            onAuto = if (total != null) ({ onChange { it.copy(totalBuildersOverride = null) } }) else null,
        )
        SwitchRow(
            "Potions and Bites also speed up the Goblin Builder",
            b.boostsApplyToGoblin,
        ) { on -> boosts { it.copy(boostsApplyToGoblin = on) } }

        Text("Notifications", style = MaterialTheme.typography.labelLarge)
        SwitchRow("Notify when a builder, the lab or the pet house is free", settings.notificationsEnabled) { on ->
            onChange { it.copy(notificationsEnabled = on) }
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.allowed(context)) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (settings.notificationsEnabled && !notificationsAllowed) {
            Text(
                "Notifications are blocked for this app, so nothing will be shown.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Allow")
                    }
                }
                OutlinedButton(onClick = { openNotificationSettings(context); permissionTick++ }) { Text("Open settings") }
            }
        }
        Text(
            "If notifications arrive late or not after a reboot, allow Auto-start and set battery use to " +
                "\"No restrictions\" for Clash Timers in the phone's app settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { openAppSettings(context) }) { Text("App settings (battery, auto-start)") }

        TextButton(onClick = {
            onChange { AppSettings(liveCountdown = it.liveCountdown, playerName = it.playerName) }
        }) { Text("Reset settings to defaults") }
    }
}

@Composable
private fun HoursStepper(label: String, seconds: Long, range: IntRange, onSet: (Long) -> Unit) {
    val h = (seconds / H).toInt()
    Stepper(
        label = label,
        value = "$h h (${h + 1}x)",
        onMinus = (h - 1).takeIf { it in range }?.let { v -> { onSet(v * H) } },
        onPlus = (h + 1).takeIf { it in range }?.let { v -> { onSet(v * H) } },
    )
}

/** null = automatic (helper level in hours from the export). */
@Composable
private fun AutoHoursStepper(label: String, override: Long?, auto: Long?, range: IntRange, onSet: (Long?) -> Unit) {
    val current = ((override ?: auto ?: 0L) / H).toInt()
    Stepper(
        label = label,
        value = if (override == null) "Auto (${auto?.let { "${it / H} h" } ?: "level"})" else "$current h",
        hint = if (override == null) "Helper level = hours saved" else null,
        onMinus = (current - 1).takeIf { it in range }?.let { v -> { onSet(v * H) } },
        onPlus = (current + 1).takeIf { it in range }?.let { v -> { onSet(v * H) } },
        onAuto = if (override != null) ({ onSet(null) }) else null,
    )
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    hint: String? = null,
    onAuto: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (onAuto != null) TextButton(onClick = onAuto) { Text("Auto") }
        // ASCII "-" / "+": the phone's font mangles some symbols.
        TextButton(onClick = { onMinus?.invoke() }, enabled = onMinus != null) { Text("-", fontWeight = FontWeight.Bold) }
        Text(value, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
        TextButton(onClick = { onPlus?.invoke() }, enabled = onPlus != null) { Text("+", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppSettings(context) }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
