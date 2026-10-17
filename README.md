# Doctor Calendar

Doctor Calendar is an Android app for managing a doctor's daily schedule:

- Morning and evening appointments
- Morning and evening therapy sessions
- One note for each day
- Optional Google Calendar synchronization
- Optional Google Drive backup for notes
- GitHub-based in-app updates

The app stores its data locally on the device. Google Calendar and Google Drive are optional integrations.

## Requirements

- Android 8.0 or later (API 26+)
- A Google account added to the device for Calendar sync
- The Google Calendar app or Android Calendar sync enabled for Calendar integration
- The Google Drive app installed and signed in for note backups

## Download

Download the latest APK from the [`appUpdate` release](https://github.com/GeorgeSakketos/DoctorCal/releases/tag/appUpdate).

Android may ask you to allow this app to install unknown apps. Updates are signed with the same release key as the installed version.

## Getting started

### Add appointments and therapy sessions

The main screen contains four sections:

| | Morning | Evening |
|---|---|---|
| Appointments | Patient cards | Patient cards |
| Therapy | Patient cards | Patient cards |

Tap **+** to add a card. Tap an existing card to edit or delete it. Use the arrows or tap the date to move between days. Add or edit the day's note below the schedule.

The layout adapts automatically:

- **Phone portrait:** one scrolling column
- **Tablet portrait:** schedule above the note
- **Landscape:** schedule on the left and note on the right

### Connect Google Calendar

1. Open **⋮ → Settings → Connect calendar**.
2. Allow calendar access.
3. Select the calendar to use.

The app uses calendars already available on the device. It does not need its own Google Cloud project, API key, OAuth client, or SHA-1 certificate. If the required Google account is not on the device, use **Add Google account to this phone** in the calendar settings.

### Back up notes to Google Drive

1. Open **⋮ → Settings → Back up now**.
2. In Android's file picker, choose Google Drive, a folder, and a file location.
3. Optionally enable **Back up automatically**.

To restore on a new device, choose **I already have a backup** in Settings and select the backup file. Restore merges notes into the device; the newer version of a note wins, and existing local notes are never deleted.

## Calendar synchronization

Doctor Calendar reads and writes the Android Calendar Provider. Android's account synchronization then transfers those changes to and from Google Calendar.

- New, edited, and deleted cards are pushed to the selected calendar.
- The app pulls events from 30 days in the past through 120 days in the future.
- Sync runs when the app opens, when you tap the sync button, after edits, when the device calendar changes, and periodically in the background.
- Untagged timed events are imported as appointments. Their morning/evening section is determined by the configured evening start hour.
- All-day events are ignored.
- Repeating events are displayed but read-only in the app; edit them in Google Calendar.
- Deleting an item in either place deletes it in the other.
- If the same item changes in both places, the local edit takes precedence.

Cards add a marker to the event description, such as `[Doctor Calendar: therapy, morning]`, so the card type and section can be restored after reinstalling or using another device.

Daily notes are not stored in Google Calendar. They are included only in the Google Drive backup.

## Notes backup

The backup is one JSON file named `doctorcal-notes.json` at the location selected in Android's file picker. The app receives access only to that file.

- **Back up now** writes all non-empty notes.
- **Back up automatically** runs daily while the device is online and skips the upload when nothing changed.
- **Restore** merges the backup into local notes.
- If the file is moved or deleted in Drive, use **Change location** or select the backup again.

## Troubleshooting calendar sync

There are two synchronization steps: Doctor Calendar writes to the device calendar, then Android uploads that calendar to Google.

1. Open **⋮ → Settings** and read any warning below the calendar name.
2. Confirm that automatic account sync and the calendar's own sync switch are enabled:
   **Android Settings → Passwords & accounts → Google → Account sync → Calendar**
3. Check the event in the Google Calendar app and at [calendar.google.com](https://calendar.google.com).
4. On an emulator, use a **Google Play** system image, add a Google account, and open Google Calendar once.
5. If the issue persists, inspect Android Studio Logcat with the filter `DoctorCal` and look for `sync ok…`, `sync failed…`, or `problems: …`.

## Building locally

### Prerequisites

- Android Studio Ladybug or newer
- JDK 17
- Android SDK 35

The repository includes the Gradle wrapper, so no separate Gradle installation is required.

Set the repository used by the in-app updater in `gradle.properties`:

```properties
GITHUB_REPO=GeorgeSakketos/DoctorCal
```

Then build or install the debug app:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

For calendar sync and Drive backup on an emulator, use a Google Play system image, add a Google account, and install Google Drive from the Play Store.

## Release automation

`.github/workflows/release.yml` builds and publishes a signed APK whenever `main` changes. It can also be started manually with `workflow_dispatch`.

Configure these repository secrets before enabling signed releases:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The workflow:

1. Builds the release APK with Java 17.
2. Uses the GitHub Actions run number as the Android `versionCode`.
3. Publishes `DoctorCalendar-<versionCode>.apk`.
4. Replaces the GitHub release tagged `appUpdate`.

The app checks that release at most every six hours, or when **⋮ → Check for updates** is selected. It offers the highest numbered APK when its version is newer than the installed version. Android always asks the user to confirm an update.

Keep the signing key unchanged. Android will reject an update signed with a different key.

## Technology

- Kotlin
- Jetpack Compose and Material 3
- Room
- WorkManager
- Kotlin Coroutines
- OkHttp for GitHub update checks
