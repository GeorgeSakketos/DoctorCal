package com.doctorcal.app.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.doctorcal.app.data.Entry
import com.doctorcal.app.data.Kind
import com.doctorcal.app.data.Shift
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

const val GOOGLE_ACCOUNT_TYPE = "com.google"

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    /** The account's own main calendar (its owner is the account itself). */
    val primary: Boolean,
) {
    val isGoogle: Boolean get() = accountType == GOOGLE_ACCOUNT_TYPE
}

data class DeviceEvent(
    /**
     * Identifies the event in our database. A plain event is its row id ("42"); every occurrence of a
     * repeating event is its own card and is keyed "<row id>@<start millis>" (those are read-only here).
     */
    val key: String,
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val kind: Kind?,
    val shift: Shift?,
)

/** Calendar-level switches that decide whether Android syncs a calendar with Google at all. */
data class CalendarFlags(val visible: Boolean, val syncEvents: Boolean)

/**
 * Reads and writes the calendars stored on the phone (Android's Calendar Provider).
 * When the doctor's Google account is added to the phone, Android's own sync adapter keeps these
 * calendars in sync with Google Calendar, so this app needs no Google sign-in of its own.
 * Blocking calls: use from Dispatchers.IO. Needs READ_CALENDAR and WRITE_CALENDAR.
 */
class DeviceCalendar(private val resolver: ContentResolver) {

    /** Calendars the user can add events to (read-only ones such as holidays are left out). */
    fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT,
        )
        val out = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI,
            projection,
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2).orEmpty()
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty().ifBlank { account },
                    account = account,
                    accountType = c.getString(3).orEmpty(),
                    primary = c.getString(4) == account,
                )
            }
        }
        return out.sortedWith(
            compareByDescending<CalendarInfo> { it.isGoogle }
                .thenBy { it.account }
                .thenByDescending { it.primary }
                .thenBy { it.name },
        )
    }

    /** Events of the calendar that were added, edited or deleted on the phone and not yet uploaded to Google. */
    fun pendingUploads(calendarId: Long): Int =
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DIRTY} = 1",
            arrayOf(calendarId.toString()),
            null,
        )?.use { it.count } ?: 0

    fun flags(calendarId: Long): CalendarFlags? =
        resolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
            arrayOf(Calendars.VISIBLE, Calendars.SYNC_EVENTS),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) CalendarFlags(visible = c.getInt(0) != 0, syncEvents = c.getInt(1) != 0) else null
        }

    /**
     * Timed events of [calendarId] starting inside the window; repeating events are expanded into occurrences.
     * Throws when the phone's calendar can't be read, so a failed read is never mistaken for "everything was deleted".
     */
    fun events(calendarId: Long, fromMs: Long, toMs: Long): List<DeviceEvent> {
        val (single, repeating) = loadEvents(calendarId, fromMs, toMs)
        return single + repeatingOccurrences(repeating, calendarId, fromMs, toMs)
    }

    /** One-off events straight from the Events table (works even if the calendar is hidden), plus the ids of repeating events. */
    private fun loadEvents(calendarId: Long, fromMs: Long, toMs: Long): Pair<List<DeviceEvent>, Set<Long>> {
        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DESCRIPTION,
            Events.DTSTART,
            Events.DTEND,
            Events.RRULE,
            Events.RDATE,
        )
        val cursor = resolver.query(
            Events.CONTENT_URI,
            projection,
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.ALL_DAY} = 0 AND " +
                "(${Events.STATUS} IS NULL OR ${Events.STATUS} != ?)",
            arrayOf(calendarId.toString(), Events.STATUS_CANCELED.toString()),
            "${Events.DTSTART} ASC",
        ) ?: throw IOException("The phone's calendar is not available right now")

        val single = mutableListOf<DeviceEvent>()
        val repeating = mutableSetOf<Long>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!c.getString(5).isNullOrBlank() || !c.getString(6).isNullOrBlank()) {
                    repeating += id
                    continue
                }
                val start = c.getLong(3)
                if (start < fromMs || start >= toMs) continue
                val end = if (c.isNull(4)) start + DEFAULT_LENGTH_MS else c.getLong(4)
                val (details, kind, shift) = decodeDescription(c.getString(2))
                single += DeviceEvent(
                    key = id.toString(),
                    title = c.getString(1).orEmpty().ifBlank { "(no title)" },
                    description = details,
                    startMs = start,
                    endMs = end,
                    kind = kind,
                    shift = shift,
                )
            }
        }
        return single to repeating
    }

    /** Occurrences of repeating events. Android only expands these for calendars that are visible. */
    private fun repeatingOccurrences(repeating: Set<Long>, calendarId: Long, fromMs: Long, toMs: Long): List<DeviceEvent> {
        if (repeating.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.DESCRIPTION,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
        )
        val cursor = resolver.query(
            uri,
            projection,
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            "${Instances.BEGIN} ASC",
        ) ?: throw IOException("The phone's calendar is not available right now")

        val out = mutableListOf<DeviceEvent>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id !in repeating || c.getInt(5) == 1) continue
                val begin = c.getLong(3)
                val (details, kind, shift) = decodeDescription(c.getString(2))
                out += DeviceEvent(
                    key = "$id@$begin",
                    title = c.getString(1).orEmpty().ifBlank { "(no title)" },
                    description = details,
                    startMs = begin,
                    endMs = c.getLong(4),
                    kind = kind,
                    shift = shift,
                )
            }
        }
        return out
    }

    /** Returns the new event's row id, or null if the provider refused the insert. */
    fun insert(calendarId: Long, e: Entry): Long? {
        val values = fields(e).apply { put(Events.CALENDAR_ID, calendarId) }
        return resolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
    }

    /** Returns false when the event no longer exists (deleted on the phone or by Google). */
    fun update(eventId: Long, e: Entry): Boolean {
        if (!exists(eventId)) return false
        return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), fields(e), null, null) > 0
    }

    fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    private fun exists(eventId: Long): Boolean =
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events._ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(eventId.toString()),
            null,
        )?.use { it.moveToFirst() } ?: false

    private fun fields(e: Entry): ContentValues {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.parse(e.date).atStartOfDay(zone).plusMinutes(e.startMinute.toLong())
        val end = start.plusMinutes(e.durationMin.toLong())
        return ContentValues().apply {
            put(Events.TITLE, e.patientName)
            put(Events.DESCRIPTION, encodeDescription(e.details, e.kind, e.shift))
            put(Events.DTSTART, start.toInstant().toEpochMilli())
            put(Events.DTEND, end.toInstant().toEpochMilli())
            put(Events.EVENT_TIMEZONE, zone.id)
        }
    }

    private companion object {
        const val DEFAULT_LENGTH_MS = 30 * 60_000L

        // The calendar provider only lets sync adapters store hidden extra properties, so the card's type and
        // shift travel as a short tag at the end of the description. It survives reinstalls and other devices.
        val TAG = Regex("""\s*\[Doctor Calendar: (appointment|therapy), (morning|evening)]\s*$""")

        fun encodeDescription(details: String, kind: Kind, shift: Shift): String =
            "${details.trim()}\n\n[Doctor Calendar: ${kind.name.lowercase()}, ${shift.name.lowercase()}]".trim()

        fun decodeDescription(raw: String?): Triple<String, Kind?, Shift?> {
            val text = raw.orEmpty()
            val m = TAG.find(text) ?: return Triple(text.trim(), null, null)
            return Triple(
                text.removeRange(m.range).trim(),
                Kind.valueOf(m.groupValues[1].uppercase()),
                Shift.valueOf(m.groupValues[2].uppercase()),
            )
        }
    }
}
