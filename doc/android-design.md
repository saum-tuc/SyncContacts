# Android and data design

## Platform integration

Use Android's Contacts Provider to enumerate accessible account-scoped contact
data and to read and write destination raw contacts and their data rows.
Android may aggregate raw contacts from several accounts into one displayed
contact; account-scoped reads, writes, and deletions must not accidentally
operate on the entire aggregate.

Request `READ_CONTACTS` and `WRITE_CONTACTS` using the runtime permission
mechanism. The permissions do not guarantee that every account is discoverable
or writable. The app must handle restrictions imposed by Android versions,
account providers, managed profiles, and individual contacts. Do not request
sync-adapter privileges or assume the app owns a third-party account. Contact
writes are local provider operations; remote delivery, if any, is controlled
by the destination account's sync adapter.

An Android `ContentObserver` can provide change hints while the app process
exists; it must not be the only mechanism used to monitor changes. Use AndroidX
WorkManager persistent unique periodic work with a four-hour repeat interval
for reconciliation and recovery. The job must remain scheduled when the app
is swiped away or its process is killed, and must be restored across reboot
without requiring an app launch. Rely on WorkManager's supported reboot
rescheduling rather than an independent duplicate scheduler; verify that its
required boot integration remains enabled in the merged manifest. Do not
schedule periodic checks while setup is incomplete or synchronization is
paused, including after a reboot.

WorkManager persists its work across app restarts and device reboots, but
actual execution is subject to Android scheduling, battery management, device
manufacturer restrictions, and credential-protected storage availability.
Checks may run earlier or later than four hours. A reboot check may need to
wait until the user unlocks the device. Do not claim continuous monitoring or
an exact deadline.

An Android system force-stop, including Force stop in app settings, is not
equivalent to swiping the app away. Android prevents the app from running and
receiving boot broadcasts while it is in the stopped state; it cannot guarantee
automatic recovery from that state, even after reboot. On a subsequent explicit
app launch, reconcile persisted scheduling and request a fresh check when sync
is enabled. Explain relevant limitations and actionable battery restrictions
in the UI rather than promising to bypass system controls. Do not require
battery-optimization exemptions or exact alarms for ordinary operation.

## Data to persist

Store app-private durable data sufficient to recover safely:

- The selected account/book identities, activation state, and configuration
  generation, with pair-specific mappings.
- Source and destination identity evidence and links; enough normalized
  comparison state to detect changes and validate previous decisions.
- Pending comparisons, decisions, operations, retry state, and errors, scoped
  to the relevant pair and observed versions.
- The last completed source scan and last successful check timestamps, clearly
  distinguished from partial scans and syncs with unresolved items.
- A durable operation journal and the user-readable log.

Persist only the contact data needed for these purposes. A provider ID or
snapshot must not be treated as proof of identity if the account or provider
has changed. Avoid storing secrets or account credentials; no network service
or app-managed login is required for the specified workflow.

## Contact data coverage

Specify and implement explicit mappings for the Android contact data types the
app supports, including at least structured names, phone numbers, email
addresses, postal addresses, organizations, notes, important dates, websites,
and photos where the destination allows them. Preserve meaningful labels and
multiple values. Support for groups, custom MIME types, account-specific
fields, and other provider data must be explicitly classified and reported;
do not silently discard unsupported source data or claim complete success.
Where the destination provider refuses a field or operation, identify the
affected contact and field and keep the issue visible.

Contact data types and account implementations differ. A field that cannot be
represented safely in the destination requires a visible issue rather than a
lossy approximation presented as a complete sync. A source scan and an
individual contact write may involve multiple provider rows; use provider
batching or a recoverable journal so partial writes are detected and retried
safely.

## Log specification

Maintain a chronological, timestamped, human-readable UTF-8 log in
app-private storage. Each entry identifies the event, selected pair, affected
contact or safe local identifier, and outcome as applicable. Include:

- Configuration changes, checks started and completed, partial scans, and
  scheduling or permission failures.
- Each detected source contact addition, modification, and deletion, including
  changed field names and observed old/new values where available. Initial
  discoveries are distinguished from later detected changes. For binary values
  such as photos, record presence, size, and a digest rather than raw binary
  contents.
- Matching results, ambiguous matches, user decisions and deferrals, and
  unsupported or rejected fields.
- Each intended destination creation, update, and deletion; the affected
  fields or contact; and whether it succeeded, failed, or remains pending.
  A no-op comparison is recorded as such, not as a destination write.
- Recovery and retry outcomes and explicit log-clearing events.

Logs can contain sensitive personal contact details. Keep them private to the
app by default, display a warning before export, and share only after a user
action. Provide temporary read-only URI grants through an Android content
provider, without making the log generally world-readable. Neither logs nor
contact data are sent to any service by SyncContacts itself. A user-selected
external app may retain or transmit an exported copy.

Record a durable intent before a destination mutation and record its outcome
afterwards. If a crash occurs between the two, recovery must inspect actual
provider state before deciding whether to retry and must log the recovery
result. If required logging or journal storage is unavailable, stop new
destination mutations and surface an error; do not silently perform unlogged
actions. The log describes observed changes, not changes that occurred and
were reversed between observations.

Retain the log until the user clears it or uninstalls
the app. Handle storage exhaustion explicitly; do not silently discard entries.
When the user clears the log, remove prior entries and start the new log with
a clearing event. Exported copies are outside the app's control.

## Reference material

- [Android Contacts Provider guide](https://developer.android.com/identity/providers/contacts-provider)
- [Android background work guidance](https://developer.android.com/develop/background-work)
- [Define WorkManager work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Secure file sharing](https://developer.android.com/training/secure-file-sharing)
