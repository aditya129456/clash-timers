package com.aditya.clashtimer.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Supercell's required wording for fan content (supercell.com/fan-content-policy). */
const val FAN_CONTENT_DISCLAIMER =
    "This material is unofficial and is not endorsed by Supercell. For more information see " +
        "Supercell's Fan Content Policy: www.supercell.com/fan-content-policy."

/** About: version, offline note, Supercell's fan content disclaimer, credits and the font licence. */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    var showLicence by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("About", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("Clash Timers $version")
        Text("Works fully offline", fontWeight = FontWeight.Bold)
        Text(
            "The app has no internet permission. It reads only the village data you copy in Clash of Clans, " +
                "keeps it on this phone and sends nothing anywhere. No account, no ads, no tracking.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(FAN_CONTENT_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        Text(
            "Clash of Clans names and images are the property of Supercell. Names, upgrade times and images " +
                "come from the clash-of-clans-data package. Font: Lilita One by Juan Montoreano, " +
                "SIL Open Font License 1.1.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showLicence = !showLicence }) {
            Text(if (showLicence) "Hide font licence" else "Show font licence")
        }
        if (showLicence) {
            val licence = remember { readAsset(context, "licenses/LilitaOne-OFL.txt") }
            Text(licence, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** `1.0 (1)` from the installed package. */
private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("")

private fun readAsset(context: Context, path: String): String =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("Licence file missing.")
