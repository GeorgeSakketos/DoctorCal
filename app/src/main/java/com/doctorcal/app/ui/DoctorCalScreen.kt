package com.doctorcal.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doctorcal.app.MainViewModel
import com.doctorcal.app.data.Entry
import com.doctorcal.app.data.Kind
import com.doctorcal.app.data.Shift
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What the entry dialog is editing / creating. */
data class EditTarget(val entry: Entry?, val kind: Kind, val shift: Shift, val defaultMinute: Int)

@Composable
fun DoctorCalScreen(vm: MainViewModel) {
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val dayEntries by vm.entries.collectAsStateWithLifecycle()
    val noteState by vm.note.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val backupBusy by vm.backupBusy.collectAsStateWithLifecycle()
    val syncProblems by vm.syncProblems.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // Android's file pickers: the doctor picks a Google Drive file once, the app keeps writing to it afterwards.
    var enableDailyAfterPick by remember { mutableStateOf(false) }
    val createBackup = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri != null) vm.backupToNewLocation(uri, enableDaily = enableDailyAfterPick)
        enableDailyAfterPick = false
    }
    val openBackup = rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        if (uri != null) vm.restoreFromNewLocation(uri)
    }
    val hasBackupLocation = settings.backupName.isNotBlank()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val entries = if (dayEntries.date == date) dayEntries.entries else emptyList()

    fun inBox(k: Kind, s: Shift) = entries.filter { it.kind == k && it.shift == s }

    // One quadrant of the 2x2 grid (or one stacked section on a phone).
    val quadrant: @Composable (Kind, Shift, Modifier, Boolean) -> Unit = { kind, shift, modifier, scrollInside ->
        val list = inBox(kind, shift)
        ShiftBox(
            kind = kind,
            shift = shift,
            entries = list,
            modifier = modifier,
            scrollInside = scrollInside,
            onAdd = {
                val next = list.maxOfOrNull { it.startMinute + it.durationMin }
                    ?: if (shift == Shift.MORNING) 9 * 60 else settings.eveningStartHour * 60
                editing = EditTarget(null, kind, shift, next.coerceAtMost(23 * 60 + 30))
            },
            onOpen = { editing = EditTarget(it, it.kind, it.shift, it.startMinute) },
        )
    }
    val notes: @Composable (Modifier) -> Unit = { modifier ->
        NotesPanel(date, noteState, modifier) { vm.saveNote(date.toString(), it) }
    }

    Scaffold(
        topBar = {
            HeaderBar(
                date = date,
                syncing = syncing,
                updateAvailable = update !is com.doctorcal.app.UpdateUi.Idle,
                onPrev = { vm.shiftDay(-1) },
                onNext = { vm.shiftDay(1) },
                onPickDate = { vm.goTo(it) },
                onToday = { vm.goTo(LocalDate.now()) },
                onSync = { vm.sync(manual = true) },
                onSettings = {
                    showSettings = true
                    vm.loadCalendars()
                },
                onCheckUpdates = { vm.checkForUpdates(manual = true) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val gap = 8.dp
            val landscape = maxWidth > maxHeight
            when {
                // Landscape (phone or tablet): 2x2 grid on the left, notes on the right.
                landscape -> Row(
                    Modifier.fillMaxSize().padding(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    QuadGrid(Modifier.weight(2f).fillMaxHeight(), gap, quadrant)
                    notes(Modifier.weight(1f).fillMaxHeight())
                }

                // Tablet portrait: 2x2 grid on top, notes underneath.
                maxWidth >= 600.dp -> Column(
                    Modifier.fillMaxSize().padding(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    QuadGrid(Modifier.weight(3f).fillMaxWidth(), gap, quadrant)
                    notes(Modifier.weight(1.1f).fillMaxWidth())
                }

                // Phone portrait: one scrolling column of the four sections, then notes.
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (kind in listOf(Kind.APPOINTMENT, Kind.THERAPY)) {
                        for (shift in listOf(Shift.MORNING, Shift.EVENING)) {
                            quadrant(kind, shift, Modifier.fillMaxWidth(), false)
                        }
                    }
                    notes(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        }
    }

    editing?.let { target ->
        EntryDialog(
            target = target,
            date = date,
            onDismiss = { editing = null },
            onSave = {
                vm.saveEntry(it)
                editing = null
            },
            onDelete = {
                vm.deleteEntry(it)
                editing = null
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            state = settings,
            calendars = calendars,
            problems = syncProblems,
            onDismiss = { showSettings = false },
            onConnect = vm::connect,
            onDisconnect = vm::disconnect,
            onSelectCalendar = vm::selectCalendar,
            onAutoSync = vm::setAutoSync,
            onSyncNow = { vm.sync(manual = true) },
            onEveningHour = vm::setEveningStartHour,
            onRepo = vm::setGithubRepo,
            onAutoUpdates = vm::setAutoCheckUpdates,
            backupBusy = backupBusy,
            onBackupNow = { if (hasBackupLocation) vm.backupNow() else createBackup.launch(BACKUP_FILE_NAME) },
            onRestore = { if (hasBackupLocation) vm.restoreNow() else openBackup.launch(arrayOf("*/*")) },
            onAutoBackup = { enabled ->
                if (enabled && !hasBackupLocation) {
                    enableDailyAfterPick = true
                    createBackup.launch(BACKUP_FILE_NAME)
                } else {
                    vm.setAutoBackup(enabled)
                }
            },
            onChangeBackupLocation = { createBackup.launch(BACKUP_FILE_NAME) },
            onRestoreFromFile = { openBackup.launch(arrayOf("*/*")) },
            onAddAccount = vm::addGoogleAccount,
            onCheckUpdates = { vm.checkForUpdates(manual = true) },
        )
    }

    UpdateDialogs(update, vm)
}

/** The 2x2 grid: rows = appointments / therapy, columns = morning / evening shift. */
@Composable
private fun QuadGrid(
    modifier: Modifier,
    gap: Dp,
    quadrant: @Composable (Kind, Shift, Modifier, Boolean) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (kind in listOf(Kind.APPOINTMENT, Kind.THERAPY)) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (shift in listOf(Shift.MORNING, Shift.EVENING)) {
                    quadrant(kind, shift, Modifier.weight(1f).fillMaxHeight(), true)
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(
    date: LocalDate,
    syncing: Boolean,
    updateAvailable: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day") }
            TextButton(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, y, m, d -> onPickDate(LocalDate.of(y, m + 1, d)) },
                        date.year, date.monthValue - 1, date.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, contentDescription = "Next day") }
            if (date != LocalDate.now()) {
                IconButton(onClick = onToday) { Icon(Icons.Default.Today, contentDescription = "Go to today") }
            }
            IconButton(onClick = onSync, enabled = !syncing) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Sync, contentDescription = "Sync with Google Calendar")
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(
                    if (updateAvailable) Icons.Default.SystemUpdate else Icons.Default.MoreVert,
                    contentDescription = "More",
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = { menu = false; onSettings() },
                )
                DropdownMenuItem(
                    text = { Text("Check for updates") },
                    leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                    onClick = { menu = false; onCheckUpdates() },
                )
            }
        }
    }
}
