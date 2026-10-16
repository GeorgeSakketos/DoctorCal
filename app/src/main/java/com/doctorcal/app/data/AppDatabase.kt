package com.doctorcal.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE date = :date AND deleted = 0 ORDER BY startMinute, patientName")
    fun observeDay(date: String): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: String): Entry?

    @Query("SELECT * FROM entries WHERE dirty = 1")
    suspend fun dirtyEntries(): List<Entry>

    @Query("SELECT * FROM entries WHERE googleEventId = :googleId LIMIT 1")
    suspend fun byGoogleId(googleId: String): Entry?

    /** Entries that were synced with [calendarId] and have no pending local edits. */
    @Query(
        "SELECT * FROM entries WHERE googleEventId IS NOT NULL AND calendarId = :calendarId " +
            "AND dirty = 0 AND deleted = 0 AND date BETWEEN :fromDate AND :toDate",
    )
    suspend fun syncedInRange(calendarId: String, fromDate: String, toDate: String): List<Entry>

    @Upsert
    suspend fun upsert(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE date = :date")
    fun observe(date: String): Flow<DayNote?>

    @Query("SELECT * FROM notes WHERE date = :date")
    suspend fun get(date: String): DayNote?

    /** Every non-empty note, for the Google Drive backup. */
    @Query("SELECT * FROM notes WHERE TRIM(text) != '' ORDER BY date")
    suspend fun all(): List<DayNote>

    @Upsert
    suspend fun upsert(note: DayNote)
}

@Database(entities = [Entry::class, DayNote::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun noteDao(): NoteDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "doctorcal.db")
                .build()
    }
}
