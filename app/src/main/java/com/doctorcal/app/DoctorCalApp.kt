package com.doctorcal.app

import android.app.Application
import com.doctorcal.app.data.AppDatabase
import com.doctorcal.app.sync.BackupManager
import com.doctorcal.app.sync.BackupWorker
import com.doctorcal.app.sync.SyncManager
import com.doctorcal.app.sync.SyncWorker
import com.doctorcal.app.update.Updater

class DoctorCalApp : Application() {
    val prefs by lazy { Prefs(this) }
    val db by lazy { AppDatabase.create(this) }
    val syncManager by lazy { SyncManager(this, db, prefs) }
    val backupManager by lazy { BackupManager(this, db, prefs) }
    val updater by lazy { Updater(this, prefs) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this, prefs.connected && prefs.autoSync)
        BackupWorker.schedule(this, prefs.autoBackup)
    }
}
