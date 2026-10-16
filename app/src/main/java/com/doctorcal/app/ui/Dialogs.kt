package com.doctorcal.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doctorcal.app.BuildConfig
import com.doctorcal.app.MainViewModel
import com.doctorcal.app.SettingsState
import com.doctorcal.app.UpdateUi
import com.doctorcal.app.data.Entry
import com.doctorcal.app.data.Kind
import com.doctorcal.app.data.formatMinute
import com.doctorcal.app.data.isRepeating
import com.doctorcal.app.sync.CalendarInfo
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
fun EntryDialog(
    target: EditTarget,
    date: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    val existing = target.entry
    if (existing != null && existing.isRepeating) {
        // One occurrence of a repeating calendar event: shown here, but edited in the calendar app.
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(existing.patientName) },
            text = {
                Text(
                    "${formatMinute(existing.startMinute)} · ${existing.durationMin} min\n\n" +
                        "This is part of a repeating event. Edit or delete it in Google Calendar and it will update here.",
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.patientName ?: "") }
    var minute by remember { mutableIntStateOf(existing?.startMinute ?: target.defaultMinute) }
    var duration by remember {
        mutableStateOf((existing?.durationMin ?: if (target.kind == Kind.THERAPY) 45 else 30).toString())
    }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New ${target.kind.label.lowercase()}" else "Edit ${target.kind.label.lowercase()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${target.shift.label} shift · ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Patient name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(context, { _, h, m -> minute = h * 60 + m }, minute / 60, minute % 60, true).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(formatMinute(minute))
                    }
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it.filter(Char::isDigit).take(3) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val base = existing ?: Entry(
                        date = date.toString(),
                        startMinute = minute,
                        patientName = name.trim(),
                        kind = target.kind,
                        shift = target.shift,
                    )
                    onSave(
                        base.copy(
                            patientName = name.trim(),
                            startMinute = minute,
                            durationMin = (duration.toIntOrNull() ?: 30).coerceIn(5, 600),
                            details = details.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.patientName}?") },
            text = { Text("This also removes it from Google Calendar if it was synced.") },
            confirmButton = {
                TextButton(onClick = { onDelete(existing) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun SettingsDialog(
    state: SettingsState,
    calendars: List<CalendarInfo>,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectCalendar: (CalendarInfo) -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onEveningHour: (Int) -> Unit,
    onRepo: (String) -> Unit,
    onAutoUpdates: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    backupBusy: Boolean,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onAutoBackup: (Boolean) -> Unit,
    onChangeBackupLocation: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onAddAccount: () -> Unit,
) {
    var repo by remember { mutableStateOf(state.githubRepo) }
    var confirmRestore by remember { mutableStateOf(false) }

    @Composable
    fun Section(title: String) = Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )

    @Composable
    fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Google Calendar")
                if (state.connected) {
                    Text(
                        "Syncing with ${state.calendarName}" +
                            if (state.account.isNotBlank() && state.account != state.calendarName) " (${state.account})" else "",
                    )
                    if (state.lastSyncAt > 0) {
                        Text(
                            "Last sync: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSyncAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Calendar to sync with:", style = MaterialTheme.typography.bodySmall)
                    if (calendars.isEmpty()) {
                        Text(state.calendarName, fontWeight = FontWeight.SemiBold)
                    } else {
                        calendars.forEach { c ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = c.id == state.calendarId, onClick = { onSelectCalendar(c) })
                                Text(if (c.name == c.account) c.account else "${c.name} · ${c.account}")
                            }
                        }
                    }
                    SwitchRow("Sync automatically", state.autoSync, onAutoSync)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSyncNow) { Text("Sync now") }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                } else {
                    Text("Uses the Google account that is signed in on this phone, so there is nothing else to log in to. Android will ask once for permission to use your calendar.")
                    Button(onClick = onConnect) { Text("Connect calendar") }
                }
                TextButton(onClick = onAddAccount) { Text("Add Google account to this phone") }

                HorizontalDivider()
                Section("Shifts")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Evening shift starts at", Modifier.weight(1f))
                    IconButton(onClick = { onEveningHour(state.eveningStartHour - 1) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Earlier")
                    }
                    Text(formatMinute(state.eveningStartHour * 60), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onEveningHour(state.eveningStartHour + 1) }) {
                        Icon(Icons.Default.Add, contentDescription = "Later")
                    }
                }
                Text(
                    "Used to place events imported from Google Calendar into the morning or evening box.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                Section("Notes backup (Google Drive)")
                Text(
                    "Saves your daily notes as one small file in your Google Drive. The first time, Android's file picker opens: choose Google Drive, then a folder, then Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (state.lastBackupAt > 0) {
                        "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastBackupAt))
                    } else {
                        "No backup yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow("Back up automatically (daily)", state.autoBackup, onAutoBackup)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onBackupNow, enabled = !backupBusy) { Text("Back up now") }
                    OutlinedButton(onClick = { confirmRestore = true }, enabled = !backupBusy) { Text("Restore") }
                    if (backupBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (state.backupName.isNotBlank()) {
                    Text(
                        "Backup file: ${state.backupName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onChangeBackupLocation) { Text("Change location") }
                        TextButton(onClick = onRestoreFromFile) { Text("Restore from another file") }
                    }
                } else {
                    TextButton(onClick = onRestoreFromFile) { Text("I already have a backup: choose it") }
                }

                HorizontalDivider()
                Section("Updates")
                Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = repo,
                    onValueChange = {
                        repo = it
                        onRepo(it)
                    },
                    label = { Text("GitHub repository (owner/name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Check for updates on start", state.autoCheckUpdates, onAutoUpdates)
                OutlinedButton(onClick = onCheckUpdates) { Text("Check for updates now") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore notes from the backup file?") },
            text = { Text("Notes from the backup are merged into this device. For a day that exists in both places, the more recently edited note is kept, and nothing is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestore()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun UpdateDialogs(update: UpdateUi, vm: MainViewModel) {
    when (update) {
        UpdateUi.Idle -> Unit

        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${update.info.versionName} is ready (you have ${BuildConfig.VERSION_NAME}).")
                    if (update.info.notes.isNotBlank()) Text(update.info.notes, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.downloadUpdate(update.info) }) { Text("Download & install") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } },
        )

        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update…") },
            text = { LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )

        is UpdateUi.Ready -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Ready to install") },
            text = { Text("Confirm the installation in the Android prompt. Doctor Calendar will restart with the new version.") },
            confirmButton = { TextButton(onClick = { vm.installUpdate(update.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Cancel") } },
        )
    }
}
