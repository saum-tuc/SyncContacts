# SyncContacts

SyncContacts is an Android app for one-way contact synchronization from a
selected source address book to a selected destination. It uses Android's
Contacts Provider, so both books must be exposed by Android; syncing a
DAVx5-managed book with its server remains the account sync adapter's job.

## Current behavior

- Settings discovers the device-local book, books with visible raw contacts,
  and accounts registered with a Contacts sync adapter. The user selects
  distinct **From** and **To** books. Accounts that Android does not expose
  cannot be listed, and provider writability is confirmed only when a write is
  attempted.
- The initial scan copies unmatched source contacts and reconciles phone-number
  matches. Matching is conservative; ambiguous matches wait for a user decision.
- Missing supported source details are added without removing destination-only
  details. Conflicting field values wait for an individual decision. Supported
  data includes names, phones, email, postal addresses, organizations, notes,
  events, websites, and photos. Unsupported data types are logged and skipped.
- Existing matches are linked durably. Deleting a linked source contact deletes
  its destination raw contact, including destination-only details; setup warns
  about this destructive behavior.
- A private log records detected source changes, destination actions, skipped
  data, errors, and user decisions. The **Log** screen can share a read-only
  copy through Android's share sheet or clear the local log.
- WorkManager checks about every four hours and requests a check after settings
  changes only when **Enable background sync** is on. This option is off by
  default. **Sync now** always runs a one-off check, even when background sync
  is disabled; it shows progress and then a scrollable list of changed contact
  names. Selecting a different pair also starts its required initial sync.
  WorkManager persists scheduled work across ordinary process termination and
  device reboot, subject to Android scheduling and battery restrictions.
  Android force-stop blocks work until the user launches the app.

## Safety limitations

- Polling cannot detect edits that are made and reversed between checks.
- Empty address books are discoverable only when represented by a visible local
  book or an account registered with a Contacts sync adapter. A provider may
  still reject writes, which are reported rather than silently treated as
  successful.
- Phone matching does not infer a country code. Unsupported or unusual phone
  values may require a manual match decision.
- A provider/process failure with an uncertain write outcome is journaled and
  is not blindly retried. Such an unresolved journal entry blocks further
  destination writes; there is not yet a user-facing recovery workflow for it.
- The log and contact database contain sensitive personal data. Logs are
  private to the app until exported or cleared.

## Build and test

Install Android SDK Platform 35 and set `ANDROID_HOME` to the SDK location, or
configure the SDK location in `local.properties`. From the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The generated debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
Instrumented tests create and delete synthetic contacts. Run them only on an
isolated test device with contacts permissions granted:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <isolated-device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <isolated-device-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <isolated-device-serial> shell pm grant org.synccontacts.app android.permission.READ_CONTACTS
adb -s <isolated-device-serial> shell pm grant org.synccontacts.app android.permission.WRITE_CONTACTS
adb -s <isolated-device-serial> shell am instrument -w org.synccontacts.app.test/androidx.test.runner.AndroidJUnitRunner
```

The Gradle `connectedDebugAndroidTest` task runs on every connected device;
avoid it when a connected phone contains real contacts.

Product requirements and design details are in [doc/](doc/README.md).
