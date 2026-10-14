package org.openbased.android.ui

import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onSignOut: () -> Unit) {
    val container = rememberContainer()
    var wifiOnly by remember { mutableStateOf(container.settings.wifiOnly) }
    var pip by remember { mutableStateOf(container.settings.pictureInPicture) }
    var dualScreen by remember { mutableStateOf(container.settings.dualScreen) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        ListItem(
            headlineContent = { Text("Server") },
            supportingContent = { Text(container.auth.serverUrl ?: "") },
        )
        ListItem(
            headlineContent = { Text("Download only on Wi-Fi") },
            supportingContent = { Text("Applies to downloads started from now on.") },
            trailingContent = {
                Switch(checked = wifiOnly, onCheckedChange = {
                    wifiOnly = it
                    container.settings.wifiOnly = it
                })
            },
        )
        ListItem(
            headlineContent = { Text("Picture-in-picture") },
            supportingContent = { Text("Keep playing in a small window when you leave the player.") },
            trailingContent = {
                Switch(checked = pip, onCheckedChange = {
                    pip = it
                    container.settings.pictureInPicture = it
                })
            },
        )
        ListItem(
            headlineContent = { Text("Use a second screen") },
            supportingContent = {
                Text("On dual-screen devices, or with an external display, play the video on the other screen " +
                    "and keep the controls on this one.")
            },
            trailingContent = {
                Switch(checked = dualScreen, onCheckedChange = {
                    dualScreen = it
                    container.settings.dualScreen = it
                })
            },
        )
        ScreensInfo()
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Sign out")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out?") },
            text = { Text("Your downloads are deleted from this device. Progress that has not been sent yet is lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** The displays Android reports, to see how a dual-screen device presents its second screen. */
@Composable
private fun ScreensInfo() {
    val context = LocalContext.current
    val screens = remember {
        context.getSystemService(DisplayManager::class.java).displays.map { d ->
            val size = Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
            val flags = listOfNotNull(
                "presentation".takeIf { d.flags and Display.FLAG_PRESENTATION != 0 },
                "private".takeIf { d.flags and Display.FLAG_PRIVATE != 0 },
                "off".takeIf { d.state == Display.STATE_OFF },
            )
            "#${d.displayId} ${d.name} · ${size.x}×${size.y}" + if (flags.isEmpty()) "" else " · ${flags.joinToString()}"
        }
    }
    ListItem(
        headlineContent = { Text("Screens") },
        supportingContent = { Text(screens.joinToString("\n")) },
    )
}

