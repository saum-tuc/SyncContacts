# Acceptance criteria

These scenarios define expected behavior for a future implementation. Test
both the initial reconciliation and later checks; use isolated test accounts
and synthetic contacts, especially for deletion tests.

## Setup and address books

1. A fresh install explains access and deletion, requests permissions, lists
   accessible books, and requires distinct From and To selections plus explicit
   confirmation before modifying contacts.
2. Denied or revoked permission, no discoverable books, a missing account, and
   a read-only destination each produce an actionable state, not a false success.
3. Settings shows the current pair. Canceling a selection change leaves it
   unchanged. Confirming a new pair requests initial reconciliation without
   deleting contacts in the previous destination.
4. Re-selecting a previously used pair validates its retained links; old
   pending operations cannot run against a different pair.

## Matching and reconciliation

5. An A contact with no matching B phone number is copied once, with its
   supported details, and is not copied again on retry or the next check.
6. A contact with no usable number follows the documented unmatched
   behavior. An ambiguous phone match or multiple A contacts competing for
   one B contact results in an individual pending decision and no unsafe write.
7. Equivalent phone numbers with supported formatting differences match;
   unrelated numbers sharing a suffix do not. Unknown country context and
   extensions do not cause guessed matches.
8. A matched pair with identical details results in no B modification.
9. If B's details are a strict subset of A's, B receives A's additional
   details without losing any existing values or labels.
10. If A's details are a strict subset of B's, B is unchanged.
11. Conflicting details result in an individual decision. Deferral leaves the
    conflict unchanged; selecting a resolution changes only the approved
    destination details. A changed contact invalidates a stale decision.
12. Repeated reconciliation does not create duplicate values. Multiple
    supported values, structured names, labels, and photos are compared and
    transferred correctly. Unsupported or rejected data is visible as an issue.
13. An added A contact follows the same matching rules as initial sync. An
    A phone-number change does not lose a valid existing link. A removed A
    detail does not remove a destination-only detail.

## Deletion and isolation

14. Deleting an A contact deletes its linked B raw contact, including an
    originally pre-existing B contact, and records the outcome.
15. No unrelated raw contact in another account, including one in the same
    aggregate, is deleted or modified. Unmatched B contacts remain untouched.
16. A failed or partial scan, permission loss, missing source account, or
    changed selection causes no mass deletion. An uncertain or missing link is
    reviewed instead of deleting a guessed destination.
17. A failed destination delete remains pending and is surfaced. A retry
    revalidates the pair, source absence, and destination identity.

## Background execution and recovery

18. Changing the selected pair requests an immediate initial sync. Background
    sync is enabled by default but stays inactive until a pair is confirmed.
    When enabled, provider notifications and unique persistent periodic work trigger
    automatic checks. Verify the selected interval (15 minutes by default),
    including every available choice, not an exact
    execution time. Manual sync remains available while background sync is off.
19. Interruptions before and after a destination write, concurrent triggers,
    and restarts do not create duplicate contacts or silently lose operations.
    Recovery checks actual provider state before retrying.
20. Source changes during an outstanding decision or queued deletion are
    rechecked before a destination mutation. Disabling background sync prevents
    new automatic work; manual sync remains available.
21. The UI distinguishes a completed check from pending decisions, partial
    work, and errors. It does not claim that every intermediate source edit
    was observed or that Android guarantees a specific execution deadline.
    On a test device, swipe the app away and separately terminate its process;
    verify that scheduled work later runs without reopening the app. Reboot
    the device, unlock it if needed, and verify that scheduled work resumes
    without opening the app. Allow for system scheduling delays. Verify that
    disabled background sync or incomplete setup stays inactive across reboot.
    Separately test
    Android force-stop: no automatic execution is promised while stopped;
    launching the app again restores scheduling and requests a check only when
    background sync is enabled.
22. **Force sync now** works with background sync disabled, shows a progress spinner,
    and displays one changed contact name per scrollable result-dialog line.
    The user can dismiss that dialog with **OK**.

## Log and privacy

Logging is disabled by default and can be enabled on the Log screen, accessible
from Settings. Disabling it stops new log entries without deleting existing
ones. The write journal remains active independently.

23. Detected additions, modifications, and deletions have timestamped log
    entries; modification entries contain the observed changed fields and
    old/new values when available. Binary content is represented by metadata
    and a digest, not raw bytes.
23. Matching, decisions, B action intents and outcomes, no-ops, failures,
    retries, and recovery are logged. A logging or journal failure prevents
    new B mutations and is surfaced.
24. The Log screen warns about personal data and opens or shares a read-only
    exported log through a user-selected app. Missing handlers and export
    failures are reported. The export does not grant write access.
25. Clearing the log requires confirmation and leaves a new clearing entry.
    The app does not upload contact data or logs on its own.
