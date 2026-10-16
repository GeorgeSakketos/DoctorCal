package com.doctorcal.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

const val BACKUP_FILE_NAME = "doctorcal-notes.json"

private const val RW_PERSIST = Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/** "Save as" picker. The chosen file stays writable so the app can refresh the backup later without asking again. */
class CreateBackupDocument : ActivityResultContracts.CreateDocument("application/json") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(RW_PERSIST)
}

/** "Open" picker for an existing backup; it also stays writable so it can become the backup location. */
class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(RW_PERSIST)
}
