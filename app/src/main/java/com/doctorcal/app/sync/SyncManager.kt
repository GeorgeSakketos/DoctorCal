package com.doctorcal.app.sync

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.doctorcal.app.Prefs
import com.doctorcal.app.data.AppDatabase
import com.doctorcal.app.data.Entry
import com.doctorcal.app.data.Kind
import com.doctorcal.app.data.Shift
import com.doctorcal.app.data.isRepeating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

sealed interface SyncOutcome {
    data class Ok(val pushed: Int, val pulled: Int) : SyncOutcome
    data object NeedsPermission : SyncOutcome
    data class Error(val message: String) : SyncOutcome
    data object NotConnected : SyncOutcome
}

/**
 * Two-way sync between the app's database and one calendar on the phone. Android's own Google sync then
 * carries that calendar to and from Google Calendar, so there is no Google sign-in inside this app.
 *
 * 1. Push: local edits/deletes (dirty rows) are written to the calendar.
 * 2. Pull: events in [today-30d, today+120d] are imported/updated; events removed from the calendar are removed locally.
 * Local edits win over remote ones for the same event because the push happens first.
 *
 * Note: Entry.googleEventId / Entry.calendarId hold the phone calendar's event key / calendar row id (kept under
 * the old column names so existing databases stay valid).
 */
class SyncManager(
    private val context: Context,
    db: AppDatabase,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()
    private val dao = db.entryDao()

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (hasPermission()) DeviceCalendar(context.contentResolver).calendars() else emptyList()
    }

    /** [requestRemote] also asks Android to sync the Google account right now so fresh changes arrive from Google. */
    suspend fun sync(requestRemote: Boolean = false): SyncOutcome {
        val calendarId = prefs.calendarId
        if (!prefs.connected || calendarId < 0) return SyncOutcome.NotConnected
        if (!hasPermission()) return SyncOutcome.NeedsPermission
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val calendar = DeviceCalendar(context.contentResolver)
                    val pushed = push(calendar, calendarId)
                    val pulled = pull(calendar, calendarId)
                    prefs.lastSyncAt = System.currentTimeMillis()
                    if (pushed > 0 || requestRemote) requestGoogleSync()
                    SyncOutcome.Ok(pushed, pulled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    SyncOutcome.NeedsPermission
                } catch (e: Exception) {
                    SyncOutcome.Error(e.message ?: "Sync failed")
                }
            }
        }
    }

    /** Best effort: nudge Android's Google Calendar sync instead of waiting for its next scheduled run. */
    private fun requestGoogleSync() {
        val name = prefs.accountName
        val type = prefs.accountType
        if (name.isBlank() || type.isBlank()) return
        try {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
        } catch (_: Exception) {
            // Sync is also scheduled by Android itself; ignoring a refused request is fine.
        }
    }

    private suspend fun push(calendar: DeviceCalendar, selectedCalendar: Long): Int {
        var count = 0
        for (e in dao.dirtyEntries()) {
            if (e.isRepeating) { // occurrences of repeating events are read-only here
                dao.upsert(e.copy(dirty = false))
                continue
            }
            val calendarId = e.calendarId?.toLongOrNull() ?: selectedCalendar
            val eventId = e.googleEventId?.toLongOrNull()
            when {
                e.deleted -> {
                    if (eventId != null) calendar.delete(eventId)
                    dao.hardDelete(e.id)
                }
                eventId == null -> markPushed(e, insert(calendar, calendarId, e), calendarId)
                calendar.update(eventId, e) -> markPushed(e, eventId, calendarId)
                // Event was removed from the calendar but edited here: recreate it.
                else -> markPushed(e, insert(calendar, calendarId, e), calendarId)
            }
            count++
        }
        return count
    }

    private fun insert(calendar: DeviceCalendar, calendarId: Long, e: Entry): Long =
        calendar.insert(calendarId, e) ?: error("Could not save to the calendar")

    /** Clears the dirty flag unless the user edited the entry again while the write was in flight. */
    private suspend fun markPushed(pushed: Entry, eventId: Long, calendarId: Long) {
        val current = dao.get(pushed.id) ?: return
        dao.upsert(
            current.copy(
                googleEventId = eventId.toString(),
                calendarId = calendarId.toString(),
                dirty = current.updatedAt != pushed.updatedAt,
            ),
        )
    }

    private suspend fun pull(calendar: DeviceCalendar, calendarId: Long): Int {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays(PULL_DAYS_BACK)
        val toDate = today.plusDays(PULL_DAYS_AHEAD)
        val remote = calendar.events(
            calendarId,
            fromDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        var changed = 0
        for (r in remote) {
            val local = dao.byGoogleId(r.key)
            if (local?.dirty == true) continue // local edits are pushed first; never overwrite them here

            val start = Instant.ofEpochMilli(r.startMs).atZone(zone)
            val startMinute = start.hour * 60 + start.minute
            val merged = Entry(
                id = local?.id ?: UUID.randomUUID().toString(),
                date = start.toLocalDate().toString(),
                startMinute = startMinute,
                durationMin = ((r.endMs - r.startMs) / 60_000).toInt().coerceIn(5, 600),
                patientName = r.title,
                details = r.description,
                kind = r.kind ?: local?.kind ?: Kind.APPOINTMENT,
                shift = r.shift ?: local?.shift ?: shiftForMinute(startMinute),
                googleEventId = r.key,
                calendarId = calendarId.toString(),
                updatedAt = System.currentTimeMillis(),
                dirty = false,
            )
            if (local == null || !local.sameContentAs(merged)) {
                dao.upsert(merged)
                changed++
            }
        }

        // Events that disappeared from the calendar inside the pulled window were deleted there.
        val remoteKeys = remote.map { it.key }.toSet()
        for (l in dao.syncedInRange(calendarId.toString(), fromDate.toString(), toDate.toString())) {
            if (l.googleEventId !in remoteKeys) {
                dao.hardDelete(l.id)
                changed++
            }
        }
        return changed
    }

    private fun shiftForMinute(minute: Int) =
        if (minute < prefs.eveningStartHour * 60) Shift.MORNING else Shift.EVENING

    private companion object {
        const val PULL_DAYS_BACK = 30L
        const val PULL_DAYS_AHEAD = 120L
    }
}
