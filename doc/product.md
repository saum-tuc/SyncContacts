# Product and user interface

## Address books

An address book is a distinct, identifiable collection of raw contacts exposed
through Android's Contacts Provider. In the UI, show a human-readable account
name and account type, with enough additional information to distinguish
similarly named books. A device-local book may be offered only when the device
actually exposes one in a way that the app can identify. An account with no
current contacts should be shown when it can be reliably discovered as a
contacts-capable account; the app must not pretend that every device account is
a writable contacts book.

The app must clearly distinguish an unavailable, inaccessible, or read-only
book from a selectable writable destination. It must not advertise a collection
as available merely because another app displays it. If writability cannot be
determined until a write is attempted, report the failure and leave the work
pending; do not report success.

## First-run flow

1. Explain that the app needs contact access, performs one-way synchronization,
   and can delete linked destination contacts when source contacts are deleted.
   In particular, warn that existing destination contacts and their extra
   information can be deleted.
2. Request the Android permissions required to read contacts and write the
   destination. Explain denial and offer a way to retry or open system
   settings when appropriate. Do not start synchronization without access.
3. Show discovered address books and let the user choose **From (A)** and
   **To (B)**. The two must be distinct. Show helpful empty, permission-denied,
   and unavailable states instead of a misleading empty list.
4. Ask the user to confirm the direction and deletion warning before enabling
   the selected pair. Do not modify either book before confirmation.
5. Save the selection, request an immediate initial reconciliation, and show
   its progress, completion, pending decisions, or an actionable error.

The user must be able to leave setup without granting access or selecting a
pair. Sync remains inactive until setup is complete.

## Main screen

Show the selected source and destination, whether synchronization is enabled,
the last successful check, and whether work, errors, or user decisions are
pending. Provide access to **Settings**, **Log**, and the pending-decision
screen. A manual **Sync now** action requests a check; it must not claim a
completed sync until work actually completes. While the app is open, display
progress and errors without blocking unrelated navigation. Explain that
background checks are scheduled roughly every four hours, that swiping the app
away does not disable them, and that Android force-stop or device battery
restrictions can prevent them until the user takes action.

## Settings

- Display and allow changing the From and To address books using the same
  discovery and validation rules as first run.
- When a pair is changed, repeat the direction and deletion warning and require
  confirmation. A canceled or invalid change leaves the active pair unchanged.
- Once confirmed, stop scheduling new work for the old pair, safely finish or
  cancel in-flight work, select the new pair, and request a new initial
  reconciliation. Never use a selection change as evidence that old source
  contacts were deleted and never clean up the old destination automatically.
- Persist mappings separately for each pair. Re-selecting a previously used
  pair may reuse its mappings only after validating their identities and
  current availability. An old pending decision must never act on the newly
  selected pair.
- Allow the user to pause and resume synchronization. Resuming requests a
  fresh check rather than assuming no changes occurred while paused.

## Individual decisions

The app must present each ambiguous match or conflicting contact separately,
with enough source and destination information to make an informed choice.
The user can defer a decision. A match decision can link one eligible
destination contact, choose that the source has no match and should be copied,
or remain pending. A conflict decision can preserve B, apply selected A values,
or defer; the UI must show the exact destination changes before confirmation.

Do not silently overwrite B when a decision is needed. A decision applies only
to the pair and the observed contact versions it describes. If either contact
changes before application, refresh the comparison and ask again where needed.
Remember a resolved conflict for unchanged values so repeated checks do not
continually ask the same question. Show pending decisions on the main screen;
background work must not depend on launching an unsolicited foreground screen.

## Log menu

The **Log** screen shows the locally stored log, its sensitive-data warning,
and actions to open it in a compatible external app, share it, and clear it
after confirmation. Opening and sharing use a chooser and temporary read-only
access to an exported log file; never expose a writable private file. If no
compatible app exists, or export fails, show an actionable error. Clearing the
log is itself recorded in the new log, without retaining its old contents.
