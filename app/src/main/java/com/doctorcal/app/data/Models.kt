package com.doctorcal.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class Kind(val label: String, val plural: String) {
    APPOINTMENT("Appointment", "appointments"),
    THERAPY("Therapy", "therapy sessions"),
}

enum class Shift(val label: String) {
    MORNING("Morning"),
    EVENING("Evening"),
}

/** One patient card: an appointment or a therapy session, in the morning or evening shift. */
@Entity(
    tableName = "entries",
    indices = [Index("date"), Index("googleEventId")],
)
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** ISO date, yyyy-MM-dd */
    val date: String,
    /** Minutes after midnight, e.g. 09:30 = 570 */
    val startMinute: Int,
    val durationMin: Int = 30,
    val patientName: String,
    val details: String = "",
    val kind: Kind,
    val shift: Shift,
    /** Key of the matching event in the phone's calendar once synced (column name kept from an earlier version) */
    val googleEventId: String? = null,
    /** Row id of the phone calendar this entry was synced with */
    val calendarId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** True while there are local changes not yet pushed to Google Calendar */
    val dirty: Boolean = true,
    /** Soft-delete flag so a deletion can be pushed to Google before the row is removed */
    val deleted: Boolean = false,
) {
    /** Compares everything the user can see/edit (ignores bookkeeping fields). */
    fun sameContentAs(o: Entry): Boolean =
        date == o.date && startMinute == o.startMinute && durationMin == o.durationMin &&
            patientName == o.patientName && details == o.details &&
            kind == o.kind && shift == o.shift
}

@Entity(tableName = "notes")
data class DayNote(
    @PrimaryKey val date: String,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Occurrence of a repeating calendar event (key "<id>@<start>"). Shown on its day but edited in the calendar app. */
val Entry.isRepeating: Boolean get() = googleEventId?.contains('@') == true

fun formatMinute(minute: Int): String =
    "%02d:%02d".format((minute / 60) % 24, minute % 60)
