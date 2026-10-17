package com.doctorcal.app

import android.content.Context
import androidx.core.content.edit

/** Small SharedPreferences wrapper for app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("doctorcal", Context.MODE_PRIVATE)

    /** True once a calendar on the phone has been chosen for syncing. */
    var connected: Boolean
        get() = sp.getBoolean("connected", false)
        set(v) = sp.edit { putBoolean("connected", v) }

    /** Account (e.g. the Google address) that owns the chosen calendar. */
    var accountName: String
        get() = sp.getString("accountEmail", "") ?: ""
        set(v) = sp.edit { putString("accountEmail", v) }

    var accountType: String
        get() = sp.getString("accountType", "") ?: ""
        set(v) = sp.edit { putString("accountType", v) }

    /** Row id of the chosen calendar in the phone's calendar provider; -1 = none. */
    var calendarId: Long
        get() = sp.getLong("deviceCalendarId", -1L)
        set(v) = sp.edit { putLong("deviceCalendarId", v) }

    var calendarName: String
        get() = sp.getString("calendarName", "") ?: ""
        set(v) = sp.edit { putString("calendarName", v) }

    var autoSync: Boolean
        get() = sp.getBoolean("autoSync", true)
        set(v) = sp.edit { putBoolean("autoSync", v) }

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("autoCheckUpdates", true)
        set(v) = sp.edit { putBoolean("autoCheckUpdates", v) }

    var githubRepo: String
        get() = sp.getString("githubRepo", BuildConfig.GITHUB_REPO) ?: BuildConfig.GITHUB_REPO
        set(v) = sp.edit { putString("githubRepo", v) }

    /** Hour (0-23) at which the evening shift starts; used to classify imported events. */
    var eveningStartHour: Int
        get() = sp.getInt("eveningStartHour", 15)
        set(v) = sp.edit { putInt("eveningStartHour", v) }

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(v) = sp.edit { putLong("lastSyncAt", v) }

    /** Since when changes have been waiting to be uploaded to Google (0 = nothing waiting). */
    var pendingSince: Long
        get() = sp.getLong("pendingSince", 0L)
        set(v) = sp.edit { putLong("pendingSince", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    /** Daily automatic notes backup. */
    var autoBackup: Boolean
        get() = sp.getBoolean("autoBackup", false)
        set(v) = sp.edit { putBoolean("autoBackup", v) }

    var lastBackupAt: Long
        get() = sp.getLong("lastBackupAt", 0L)
        set(v) = sp.edit { putLong("lastBackupAt", v) }

    /** The backup file the user picked in the system file picker (a Google Drive file), "" = none yet. */
    var backupUri: String
        get() = sp.getString("backupUri", "") ?: ""
        set(v) = sp.edit { putString("backupUri", v) }

    var backupName: String
        get() = sp.getString("backupName", "") ?: ""
        set(v) = sp.edit { putString("backupName", v) }
}
