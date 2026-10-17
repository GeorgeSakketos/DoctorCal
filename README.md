# Doctor Calendar (Android)

A personal calendar for a doctor: morning/evening **appointments**, morning/evening **therapy sessions**, and a **daily note**, synced with **Google Calendar**, with a **notes backup to Google Drive** and **self-update** from GitHub.

There is **no Google Cloud project, API key, OAuth client or SHA-1 to set up.** The app uses the Google account that is already signed in on the phone.

## Layout

| Screen | Layout |
|---|---|
| Landscape (phone or tablet) | 2×2 grid on the left (rows: appointments / therapy, columns: morning / evening), notes on the right |
| Tablet portrait (width ≥ 600 dp) | 2×2 grid on top, notes underneath |
| Phone portrait | One scrolling column: 4 sections, then notes |

Each appointment/therapy is a patient card (name + hour). Tap a card to edit/delete, tap **+** in a box to add. Use ‹ › or tap the date to change day.

## Using it (the doctor's side)

1. **Google Calendar:** open *⋮ → Settings → Connect calendar*, allow the calendar permission, done. The app picks the Google account's main calendar; you can switch to another in the same screen. If the phone has no Google account yet, tap *Add Google account to this phone* and sign in with Android's normal login screen.
2. **Notes backup:** *Settings → Back up now* (or switch on *Back up automatically*). Android's file picker opens once: choose **Google Drive**, a folder, and Save. From then on the app refreshes that same file (daily if automatic backup is on).
3. **New phone / reinstall:** *Settings → I already have a backup: choose it*, pick the file in Drive.

## How the calendar sync works
The app reads and writes the **calendars stored on the phone** (Android's Calendar Provider). Android's own Google sync then carries them to and from Google Calendar, which is why no separate Google sign-in is needed. Requirements: the Google account is added on the phone and Calendar sync is on for it (*Android Settings → Passwords & accounts → Google → Account sync*).

* **Push**: new/edited/deleted cards are written to the chosen calendar; the app then asks Android to sync the account straight away.
* **Pull**: events from 30 days back to 120 days ahead are imported, and events deleted in the calendar are removed from the app. Whenever the app opens (and on the sync button) it asks Android to fetch the latest from Google, waits for that to finish and reads the calendar again; it also refreshes whenever the phone's calendar changes while the app is running. Untagged events become appointments, placed in the morning or evening box by start time (evening start hour is configurable). All-day events are skipped.
* Cards carry a small tag at the end of the event description (`[Doctor Calendar: therapy, morning]`) so type and shift survive reinstalls and other devices. It is visible in Google Calendar.
* **Repeating events** are shown on each day they occur but are read-only in the app; change them in Google Calendar.
* Deleting in either place deletes in the other. If both sides changed the same event, the local edit wins.
* Background sync every 30 min while connected, also after each edit and on app start.
* Daily notes are not part of the calendar sync; they are backed up to Drive instead.

## If calendar sync doesn't work
The app saves appointments into the calendar on the phone; **Android** then uploads them to Google. So there are two hops, and the app can only control the first:
1. Open *⋮ → Settings*. Red warnings under the calendar name list what Android-side setting is blocking sync (automatic sync off, the calendar's own sync switch off, a phone-only calendar, or changes stuck waiting several minutes to upload). The same warning appears after tapping the sync button.
2. Open the **Google Calendar** app (or calendar.google.com) and check the event appears there. If it is in Google Calendar on the phone but not on the web, Android hasn't uploaded it yet: check the phone's internet and *Settings → Passwords & accounts → your Google account → Account sync → Calendar*.
3. On an emulator, use a **Google Play** system image, add the Google account, and open the Google Calendar app once so the account's calendar is created.
4. Still stuck? In Android Studio's Logcat filter by `DoctorCal` and send the lines (`sync ok…`, `sync failed…`, `problems: …`).

## How the notes backup works
* One JSON file (`doctorcal-notes.json`) that the user picked in the system file picker. The app only gets access to that single file, not to the rest of the Drive. Because it is a normal Drive file, it is visible in Drive and can be moved or deleted there (if so, tap *Change location*).
* **Back up now** writes all non-empty notes to it. **Back up automatically (daily)** repeats it once a day while online and skips the write when no note changed.
* **Restore** merges the file into the device: for each day the more recently edited note wins, and nothing local is ever deleted.
* Needs the Google Drive app on the phone, signed in to the same account.

## GitHub self-update setup (developer, one time)

1. Push this project to a **public** GitHub repository (the app reads the release without a token).
2. Set `GITHUB_REPO=owner/name` in `gradle.properties` (or type it in the app under *Settings → Updates*).
3. Create a signing key. It must never change, or updates will refuse to install:
```bash
keytool -genkeypair -v -keystore release.jks -alias doctorcal -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # value for KEYSTORE_BASE64
```
Add these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Every push to `main` runs `.github/workflows/release.yml`, which builds a signed APK named `DoctorCalendar-<build>.apk` and republishes it as the release tagged **`appUpdate`**. Install the first APK manually from that release; after that the app updates itself.

On start (at most every 6 h) and via *⋮ → Check for updates*, the app reads `https://api.github.com/repos/<repo>/releases/tags/appUpdate`, takes the highest `…-<number>.apk` asset and compares `<number>` with its own `versionCode`. If newer, it downloads the APK and opens Android's installer. Android always asks the user to confirm, and the first time it asks to allow "install unknown apps" for this app.

## Build and test
Open in Android Studio (Ladybug or newer); the Gradle wrapper (8.9) is included. Min Android 8.0 (API 26). To try calendar sync and the backup on an emulator, use a system image marked **Google Play**, add a Google account under *Settings → Passwords & accounts*, and install the Google Drive app from the Play Store for the backup.
