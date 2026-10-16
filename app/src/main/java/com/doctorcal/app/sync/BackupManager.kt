package com.doctorcal.app.sync

import android.content.Context
import android.net.Uri
import com.doctorcal.app.Prefs
import com.doctorcal.app.data.AppDatabase
import com.doctorcal.app.data.DayNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

sealed interface BackupOutcome {
    data class Backed(val count: Int) : BackupOutcome
    data class Restored(val added: Int, val updated: Int, val inBackup: Int) : BackupOutcome
    data object NoBackup : BackupOutcome
    data object NeedsLocation : BackupOutcome
    data class Error(val message: String) : BackupOutcome
}

/**
 * Backs up the daily notes as one JSON file the user picked once in Android's file picker (normally a file in
 * Google Drive). Android's Drive provider uploads it, so the app needs no Google sign-in or API access of its own.
 * Restore merges: for each date the newer version wins, nothing local is ever deleted.
 */
class BackupManager(
    private val context: Context,
    db: AppDatabase,
    private val prefs: Prefs,
) {
    private val noteDao = db.noteDao()
    private val mutex = Mutex()

    /** [onlyIfChanged] lets the daily worker skip the write when no note changed since the last backup. */
    suspend fun backup(onlyIfChanged: Boolean = false): BackupOutcome = withLocation { uri ->
        val startedAt = System.currentTimeMillis()
        val notes = noteDao.all()
        val newest = notes.maxOfOrNull { it.updatedAt } ?: 0L
        if (onlyIfChanged && newest <= prefs.lastBackupAt) {
            return@withLocation BackupOutcome.Backed(notes.size)
        }

        val json = JSONObject()
            .put("app", "doctorcal")
            .put("version", 1)
            .put("exportedAt", startedAt)
            .put(
                "notes",
                JSONArray().also { arr ->
                    notes.forEach {
                        arr.put(JSONObject().put("date", it.date).put("text", it.text).put("updatedAt", it.updatedAt))
                    }
                },
            )
        write(uri, json.toString())
        prefs.lastBackupAt = startedAt
        BackupOutcome.Backed(notes.size)
    }

    suspend fun restore(): BackupOutcome = withLocation { uri ->
        val text = read(uri)
        if (text.isBlank()) return@withLocation BackupOutcome.NoBackup
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return@withLocation BackupOutcome.Error("That file is not a Doctor Calendar backup")
        }
        if (root.optString("app") != "doctorcal") {
            return@withLocation BackupOutcome.Error("That file is not a Doctor Calendar backup")
        }
        val notes = root.optJSONArray("notes") ?: JSONArray()

        var added = 0
        var updated = 0
        for (i in 0 until notes.length()) {
            val o = notes.getJSONObject(i)
            val date = o.optString("date")
            val noteText = o.optString("text")
            val stamp = o.optLong("updatedAt", 0L)
            if (noteText.isBlank() || runCatching { LocalDate.parse(date) }.isFailure) continue

            val local = noteDao.get(date)
            when {
                local == null -> {
                    noteDao.upsert(DayNote(date, noteText, stamp))
                    added++
                }
                local.text != noteText && stamp > local.updatedAt -> {
                    noteDao.upsert(DayNote(date, noteText, stamp))
                    updated++
                }
            }
        }
        BackupOutcome.Restored(added, updated, notes.length())
    }

    private suspend fun withLocation(block: suspend (Uri) -> BackupOutcome): BackupOutcome {
        val saved = prefs.backupUri
        if (saved.isBlank()) return BackupOutcome.NeedsLocation
        val uri = Uri.parse(saved)
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    BackupOutcome.Error("Lost access to the backup file. Open Settings and tap Change location.")
                } catch (e: FileNotFoundException) {
                    BackupOutcome.Error("The backup file could not be opened (was it deleted from Drive?). Tap Change location.")
                } catch (e: IOException) {
                    BackupOutcome.Error("Could not reach the backup file. Check your connection and try again.")
                } catch (e: Exception) {
                    BackupOutcome.Error(e.message ?: "Backup failed")
                }
            }
        }
    }

    private fun write(uri: Uri, content: String) {
        val resolver = context.contentResolver
        // "wt" truncates; some providers only know "w", which is fine because the new content is written from the start.
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("Cannot open the backup file")
        out.bufferedWriter().use { it.write(content) }
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Cannot open the backup file")
}
