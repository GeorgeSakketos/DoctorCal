package com.doctorcal.app

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doctorcal.app.data.DayNote
import com.doctorcal.app.data.Entry
import com.doctorcal.app.sync.BackupOutcome
import com.doctorcal.app.sync.BackupWorker
import com.doctorcal.app.sync.CalendarInfo
import com.doctorcal.app.sync.GOOGLE_ACCOUNT_TYPE
import com.doctorcal.app.sync.SyncOutcome
import com.doctorcal.app.sync.SyncWorker
import com.doctorcal.app.update.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

data class DayEntries(val date: LocalDate, val entries: List<Entry>)
data class NoteState(val date: String, val text: String)

data class SettingsState(
    val connected: Boolean,
    /** Account that owns the synced calendar, e.g. the Google address */
    val account: String,
    val calendarId: Long,
    val calendarName: String,
    val autoSync: Boolean,
    val autoCheckUpdates: Boolean,
    val githubRepo: String,
    val eveningStartHour: Int,
    val lastSyncAt: Long,
    val autoBackup: Boolean,
    val lastBackupAt: Long,
    /** File name of the chosen backup file; empty = no backup location chosen yet */
    val backupName: String,
)

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUi
    data class Ready(val info: UpdateInfo, val file: File) : UpdateUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DoctorCalApp
    private val entryDao = app.db.entryDao()
    private val noteDao = app.db.noteDao()
    private val prefs = app.prefs

    val selectedDate = MutableStateFlow(LocalDate.now())

    val entries: StateFlow<DayEntries> = selectedDate
        .flatMapLatest { d -> entryDao.observeDay(d.toString()).map { DayEntries(d, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayEntries(LocalDate.now(), emptyList()))

    val note: StateFlow<NoteState?> = selectedDate
        .flatMapLatest { d -> noteDao.observe(d.toString()).map { NoteState(d.toString(), it?.text ?: "") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Android-side reasons the calendar can't reach Google (sync switched off, local-only calendar, ...). */
    private val _syncProblems = MutableStateFlow<List<String>>(emptyList())
    val syncProblems: StateFlow<List<String>> = _syncProblems.asStateFlow()

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    /** Set to true when the UI should show Android's calendar permission prompt. */
    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // Re-sync when the phone's calendar changes (for example when Google delivers an edit made elsewhere).
    private var resyncJob: Job? = null
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            resyncJob?.cancel()
            resyncJob = viewModelScope.launch {
                delay(1_500)
                if (prefs.connected && prefs.autoSync) sync(manual = false)
            }
        }
    }

    private var lastForegroundSync = 0L

    /** Called whenever the app comes to the front: pick up whatever changed in Google Calendar meanwhile. */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (!prefs.connected || !prefs.autoSync || now - lastForegroundSync < 20_000) return
        lastForegroundSync = now
        startObserving()
        sync(manual = false, askGoogle = true)
    }

    init {
        if (prefs.connected) startObserving()
        val sixHours = 6 * 60 * 60 * 1000L
        if (prefs.autoCheckUpdates && System.currentTimeMillis() - prefs.lastUpdateCheck > sixHours) {
            checkForUpdates(manual = false)
        }
    }

    override fun onCleared() {
        if (observing) app.contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }

    private fun startObserving() {
        if (observing || !app.syncManager.hasPermission()) return
        app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        observing = true
    }

    private fun stopObserving() {
        if (!observing) return
        app.contentResolver.unregisterContentObserver(observer)
        observing = false
    }

    private fun readSettings() = SettingsState(
        connected = prefs.connected,
        account = prefs.accountName,
        calendarId = prefs.calendarId,
        calendarName = prefs.calendarName,
        autoSync = prefs.autoSync,
        autoCheckUpdates = prefs.autoCheckUpdates,
        githubRepo = prefs.githubRepo,
        eveningStartHour = prefs.eveningStartHour,
        lastSyncAt = prefs.lastSyncAt,
        autoBackup = prefs.autoBackup,
        lastBackupAt = prefs.lastBackupAt,
        backupName = if (prefs.backupUri.isBlank()) "" else prefs.backupName.ifBlank { "Backup file" },
    )

    private fun refreshSettings() {
        _settings.value = readSettings()
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ---- Day navigation ------------------------------------------------------------------

    fun goTo(date: LocalDate) {
        selectedDate.value = date
    }

    fun shiftDay(delta: Long) {
        selectedDate.update { it.plusDays(delta) }
    }

    // ---- Entries & notes -----------------------------------------------------------------

    fun saveEntry(entry: Entry) {
        viewModelScope.launch {
            entryDao.upsert(entry.copy(updatedAt = System.currentTimeMillis(), dirty = true, deleted = false))
            syncSoon()
        }
    }

    fun deleteEntry(entry: Entry) {
        viewModelScope.launch {
            if (entry.googleEventId == null) {
                entryDao.hardDelete(entry.id)
            } else {
                // Keep the row (hidden) until the deletion has been pushed to the calendar.
                entryDao.upsert(entry.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
                syncSoon()
            }
        }
    }

    fun saveNote(date: String, text: String) {
        viewModelScope.launch { noteDao.upsert(DayNote(date, text)) }
    }

    private fun syncSoon() {
        if (prefs.connected && prefs.autoSync) sync(manual = false)
    }

    // ---- Calendar (the phone's own calendar, kept in sync with Google by Android) --------------

    /** Starts the Connect flow: ask for calendar permission if needed, then pick the calendar to sync with. */
    fun connect() {
        if (app.syncManager.hasPermission()) pickCalendar() else _needsPermission.value = true
    }

    fun permissionRequested() {
        _needsPermission.value = false
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            pickCalendar()
        } else {
            message("Calendar permission is needed to sync. You can allow it in Android Settings > Apps > Doctor Calendar.")
        }
    }

    private fun pickCalendar() {
        viewModelScope.launch {
            val list = app.syncManager.calendars()
            _calendars.value = list
            if (list.isEmpty()) {
                message("No calendar found on this phone. Tap \"Add Google account\", sign in, then try again.")
                return@launch
            }
            // Keep the previous choice if it still exists, otherwise take the first Google calendar (its owner's main one).
            applyCalendar(list.firstOrNull { it.id == prefs.calendarId } ?: list.first())
            sync(manual = true)
        }
    }

    private fun applyCalendar(calendar: CalendarInfo) {
        prefs.calendarId = calendar.id
        prefs.calendarName = calendar.name
        prefs.accountName = calendar.account
        prefs.accountType = calendar.accountType
        prefs.connected = true
        SyncWorker.schedule(app, prefs.autoSync)
        startObserving()
        refreshSettings()
    }

    fun selectCalendar(calendar: CalendarInfo) {
        applyCalendar(calendar)
        sync(manual = true)
    }

    fun loadCalendars() {
        if (!app.syncManager.hasPermission()) return
        viewModelScope.launch {
            _calendars.value = app.syncManager.calendars()
            _syncProblems.value = app.syncManager.problems()
        }
    }

    fun disconnect() {
        prefs.connected = false
        SyncWorker.schedule(app, false)
        stopObserving()
        _calendars.value = emptyList()
        _syncProblems.value = emptyList()
        refreshSettings()
        message("Calendar sync is off. Your appointments stay on this device and in your calendar.")
    }

    /** Opens Android's own "add Google account" screen. */
    fun addGoogleAccount() {
        app.startActivity(
            Intent(Settings.ACTION_ADD_ACCOUNT)
                .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf(GOOGLE_ACCOUNT_TYPE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * [askGoogle]: also ask Android to fetch the latest from Google first and re-read the calendar afterwards, so
     * changes made on the web or another device (including deletions) arrive. Defaults to on for manual syncs.
     */
    fun sync(manual: Boolean = true, askGoogle: Boolean = manual) {
        if (!prefs.connected) {
            if (manual) message("Connect your calendar in Settings first")
            return
        }
        viewModelScope.launch {
            if (manual) _syncing.value = true
            val outcome = app.syncManager.sync(requestRemote = askGoogle)
            _syncing.value = false
            val problems = app.syncManager.problems()
            _syncProblems.value = problems
            when (outcome) {
                is SyncOutcome.Ok -> if (manual) {
                    // Saved on the phone is not the same as reaching Google: say so when Android won't carry it there.
                    message(
                        problems.firstOrNull()
                            ?: "Synced with ${prefs.calendarName}: ${outcome.pushed} sent, ${outcome.pulled} updated",
                    )
                }
                SyncOutcome.NeedsPermission -> if (manual) _needsPermission.value = true
                is SyncOutcome.Error -> if (manual) message(outcome.message)
                SyncOutcome.NotConnected -> Unit
            }
            refreshSettings()
        }
    }

    // ---- Settings ------------------------------------------------------------------------

    fun setAutoSync(enabled: Boolean) {
        prefs.autoSync = enabled
        SyncWorker.schedule(app, prefs.connected && enabled)
        refreshSettings()
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.autoCheckUpdates = enabled
        refreshSettings()
    }

    fun setGithubRepo(repo: String) {
        prefs.githubRepo = repo.trim()
        refreshSettings()
    }

    fun setEveningStartHour(hour: Int) {
        prefs.eveningStartHour = hour.coerceIn(11, 21)
        refreshSettings()
    }

    // ---- Updates -------------------------------------------------------------------------

    fun checkForUpdates(manual: Boolean = true) {
        if (manual) message("Checking for updates…")
        viewModelScope.launch {
            try {
                val info = app.updater.check()
                if (info != null) {
                    _update.value = UpdateUi.Available(info)
                } else if (manual) {
                    message("You have the latest version (${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                if (manual) message(e.message ?: "Update check failed")
            }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            _update.value = UpdateUi.Downloading(info, 0f)
            try {
                val file = app.updater.download(info) { p -> _update.value = UpdateUi.Downloading(info, p) }
                _update.value = UpdateUi.Ready(info, file)
                installUpdate(file)
            } catch (e: Exception) {
                _update.value = UpdateUi.Idle
                message(e.message ?: "Update download failed")
            }
        }
    }

    fun installUpdate(file: File) {
        if (!app.updater.canInstall()) {
            message("Allow Doctor Calendar to install apps, then tap Install again")
            app.updater.openInstallPermissionSettings()
            return
        }
        app.updater.install(file)
    }

    fun dismissUpdate() {
        _update.value = UpdateUi.Idle
    }

    // ---- Notes backup (a file in Google Drive, chosen once in the system file picker) ----------

    fun backupNow() = runBackup { app.backupManager.backup() }

    fun restoreNow() = runBackup { app.backupManager.restore() }

    /** The user just picked where to save the backup: remember it (optionally turn on daily backups) and back up. */
    fun backupToNewLocation(uri: Uri, enableDaily: Boolean) {
        if (!setBackupLocation(uri)) return
        if (enableDaily) setAutoBackup(true)
        backupNow()
    }

    /** The user just picked an existing backup file: remember it as the location and merge its notes in. */
    fun restoreFromNewLocation(uri: Uri) {
        if (setBackupLocation(uri)) restoreNow()
    }

    fun setAutoBackup(enabled: Boolean) {
        prefs.autoBackup = enabled
        BackupWorker.schedule(app, enabled)
        refreshSettings()
    }

    private fun setBackupLocation(uri: Uri): Boolean {
        val resolver = app.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            message("That location can't be used for backups. Choose a file in Google Drive.")
            return false
        }
        val old = prefs.backupUri
        if (old.isNotBlank() && old != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        prefs.backupUri = uri.toString()
        prefs.backupName = displayName(uri) ?: "Backup file"
        if (uri.authority?.contains("google", ignoreCase = true) != true) {
            message("Saved. This doesn't look like Google Drive, so it won't be backed up online.")
        }
        refreshSettings()
        return true
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun runBackup(block: suspend () -> BackupOutcome) {
        if (_backupBusy.value) return
        viewModelScope.launch {
            _backupBusy.value = true
            val outcome = block()
            _backupBusy.value = false
            when (outcome) {
                is BackupOutcome.Backed -> message(
                    if (outcome.count == 0) "No notes to back up yet" else "Backed up ${outcome.count} notes",
                )
                is BackupOutcome.Restored -> message(
                    if (outcome.added + outcome.updated == 0) "Your notes are already up to date"
                    else "Restored ${outcome.added} new and ${outcome.updated} updated notes",
                )
                BackupOutcome.NoBackup -> message("The backup file is empty")
                BackupOutcome.NeedsLocation -> message("Choose where to save the backup first")
                is BackupOutcome.Error -> message(outcome.message)
            }
            refreshSettings()
        }
    }
}
