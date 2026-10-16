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

    /** Timed events of [calendarId] that fall in the window; repeating events are expanded into occurrences. */
    fun events(calendarId: Long, fromMs: Long, toMs: Long): List<DeviceEvent> {
        val repeating = repeatingEventIds(calendarId)
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
        val out = mutableListOf<DeviceEvent>()
        resolver.query(
            uri,
            projection,
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 1) continue // all-day events don't map to a timed card
                val id = c.getLong(0)
                val begin = c.getLong(3)
                val (details, kind, shift) = decodeDescription(c.getString(2))
                out += DeviceEvent(
                    key = if (id in repeating) "$id@$begin" else id.toString(),
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

    private fun repeatingEventIds(calendarId: Long): Set<Long> {
        val ids = mutableSetOf<Long>()
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND " +
                "(${Events.RRULE} IS NOT NULL OR ${Events.RDATE} IS NOT NULL)",
            arrayOf(calendarId.toString()),
            null,
        )?.use { while (it.moveToNext()) ids += it.getLong(0) }
        return ids
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
