# SyncContacts specification

## Purpose and scope

SyncContacts is an Android app that copies contact changes in one direction,
from a user-selected source address book A to a user-selected destination
address book B. The source is never edited by the app. A destination may, for
example, be an address book managed by DAVx5; syncing that account with its
server remains the responsibility of the account's own sync adapter.

This directory is a specification, not an implementation. It describes the
requested behavior and the proposed technical approach. It does not imply that
the app, Android project, or any of its features already exists.

## Documents

- [Product and user interface](product.md): setup, settings, permissions,
  status, and log access.
- [Synchronization rules](synchronization.md): matching, reconciliation,
  deletions, background processing, and failure handling.
- [Android and data design](android-design.md): platform integration,
  persistence, logging, privacy, and platform limitations.
- [Acceptance criteria](acceptance.md): scenarios to verify before release.

## Core requirements

1. On first run and in settings, show the available address books and allow
   the user to select distinct source and destination books.
2. After selection or a change of selection, perform an initial reconciliation.
   Copy contacts present only in A to B and add information from A to matching
   contacts in B without discarding destination-only information.
3. Match contacts by phone number. When matching or data is ambiguous or
   conflicting, ask the user rather than guessing.
4. When background sync is enabled, check A for additions, modifications, and
   deletions using persistent checks at the selected interval. Continue after
   ordinary app closure or process termination and automatically resume after
   device reboot, subject to Android's scheduling and lifecycle limits. Apply
   the same matching and reconciliation rules to later changes.
5. When a linked source contact is deleted, delete its linked destination
   contact, including a contact that existed before initial synchronization.
6. When the user enables logging, maintain a log of detected source changes
   and actions on B, and let the user open or share it with another app.

## Important constraints

- The app can only work with address books that the Android Contacts Provider
  exposes to it and that it is permitted to access. Not every contact app or
  account necessarily exposes a separate writable address book.
- Background scheduling is best effort, not a real-time guarantee. The app
  cannot guarantee recording intermediate changes that occur between
  observations; the log records changes it actually detects.
- Swiping the app away or ordinary process termination must not disable
  scheduled work. An Android system force-stop is different: Android prevents
  the app from running or receiving boot broadcasts until the user explicitly
  launches it again. Device-specific battery restrictions can also delay or
  prevent work.
- The deletion rule is destructive: the destination contact and its
  destination-only information can be removed even when the app did not
  originally create that contact. Setup must explain this before enabling sync.

## Agreed behavior

- Background sync is enabled by default; logging is disabled by default.
  When background sync is enabled and a pair is confirmed, the periodic
  check interval defaults to 15 minutes and is configurable up to 24 hours, subject to Android
  scheduling, and provider notifications can request checks while the app is
  active. A changed address-book selection starts an initial sync regardless
  of the background setting.
- A source contact with no usable phone number is treated as unmatched and
  copied to B. The app warns that an existing destination contact without a
  matching phone number cannot be recognized automatically.
- An ambiguous phone-number match is resolved by the user; no destination
  contact is created or modified for that source contact while it is pending.
- A removed source detail is not automatically removed from B: under the
  requested subset rule it becomes destination-only information. Deleting the
  whole linked source contact, however, deletes the linked contact in B.
- Logs include contact details and must be treated as sensitive personal data.
  The log is retained locally until the user clears it or uninstalls the app.
