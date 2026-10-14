package org.openbased.android.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onSignOut: () -> Unit) {
    val container = rememberContainer()
    var wifiOnly by remember { mutableStateOf(container.settings.wifiOnly) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
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
