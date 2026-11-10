# Synchronization rules

## Direction and identity

All synchronization is A to B. Other than changes explicitly approved in a
conflict decision, B is never used to update A. A source contact is the contact
data belonging to the selected source book, not all data in an Android
aggregated contact. Similarly, writes and deletes are limited to the selected
destination book; another account's raw contacts must never be changed.

Persist a pair-specific link between the source and destination provider
identities after a match or successful copy. A linked contact remains linked
when its phone number changes or disappears. Validate links on every run:
provider IDs alone must not be trusted after account removal, restoration,
contact replacement, or other identity uncertainty. A link that cannot be
validated is a reviewable issue, not permission to delete or overwrite a
different contact.

## Initial reconciliation and matching

Read a complete, stable-enough view of both selected books, then process each
source contact:

1. Reuse an existing valid link for this pair if one exists.
2. Otherwise compare its phone numbers against destination phone numbers.
   Normalize presentation differences such as spaces and punctuation. Where
   a reliable country context is available, compare normalized international
   numbers; do not equate numbers solely by a suffix or guess an unknown
   country. Do not treat blank or invalid numbers as matches.
3. If exactly one eligible B contact matches and linking it does not conflict
   with another source link, link the contacts and compare their details.
4. If multiple B contacts match, a B contact would link to multiple A contacts,
   or the match is otherwise uncertain, ask the user. Do not create, change,
   or delete a destination contact for that source while the decision is
   pending.
5. If no B contact matches, create a contact in B containing the supported
   details from A and persist the link. This includes contacts with no usable
   phone number.

Do not delete destination-only contacts during initial reconciliation. A
successful local link is metadata, not a modification to B; when details are
identical, linking alone is allowed and B remains unchanged.

Normalization and equality must be deterministic and tested. Phone extensions,
multiple numbers per contact, and country context must not produce an
unreviewed false positive. If an identity cannot be determined safely, defer it
for review. Matching is not based on display name alone.

## Comparing linked contact details

Use the following rules both initially and for every later reconciliation:

| Comparison | Required behavior |
| --- | --- |
| All details match | Do not modify B. |
| All B details exist in A and A has additional details | Add the missing A details to B. |
| All A details exist in B and B has additional details | Do not modify B. |
| A and B have conflicting information | Ask the user individually before applying conflicting changes. |

Preserve destination-only information during additions. Compare values together
with meaningful type and label metadata; don't silently drop labels, structured
name components, or other supported data. Avoid duplicate equivalent values.
Multiple values are not automatically a conflict merely because one side has
extra values. Incompatible values for a logically single-valued field, or
values whose safe combination cannot be established, require a decision.
Where a contact has both nonconflicting additions and conflicts, the app may
apply the additions independently, but must log them and clearly leave the
conflicting part pending.

The implementation must define and test equality, multiplicity, and conflict
semantics for each supported contact data type. Unknown or unsupported data
must be reported rather than silently claimed as synchronized. Provider-owned,
read-only, and account-specific metadata must not be copied as ordinary
user-editable data. Photos and other binary data need binary-safe comparison
and transfer; they must not be reduced to their display text.

If a source detail is removed, the subset rule means the corresponding
destination-only detail is preserved. If a changed source detail conflicts
with a destination detail, ask the user rather than assuming the source wins.
Resolving a conflict must not discard unrelated destination details.

## Subsequent observations and deletions

After an initial scan, compare observed source state with the last successfully
recorded state to detect added, changed, and deleted source contacts. For
additions, use the same matching and copying process as initial reconciliation.
For changes, recompare the linked contacts with the rules above. If a linked
destination contact is missing or its identity is uncertain, report the issue
and require review instead of blindly overwriting another contact.

If a source contact is deleted, delete its validated linked contact in B,
including an existing B contact linked during initial reconciliation. This
deletes that destination raw contact and its destination-only data; it must not
delete raw contacts belonging to other books in the same Android aggregate.
If there is no validated link, log the deletion and take no destructive action
against B. A pending ambiguous match is not a validated link. Once a deletion
has succeeded, retire the link and record the result. If it fails, retain
the pending action and report the error.

Absence is evidence of deletion only after a complete successful scan of the
same accessible source book. A permission loss, account removal, inaccessible
provider, partial query, canceled scan, changed selection, or uncertain
identity must never be interpreted as mass deletion. Before executing a queued
delete, revalidate the active pair, link, source absence, and destination
identity. Serialize work or otherwise prevent overlapping runs from producing
duplicates or executing stale decisions.

## Execution and reliability

- Request initial work immediately after confirmation and when settings change.
  While the app process is active, contact-provider notifications can trigger
  a check. Schedule unique persistent periodic work with a four-hour interval;
  Android may run it somewhat earlier or later, and exact timing is not required.
- Keep monitoring scheduled after the user swipes the app away or Android
  terminates its process. Automatically restore or continue scheduled work
  after device reboot without requiring the user to open the app, once Android
  permits execution and the required credential-protected data is available.
  Respect the default-off background-sync setting and incomplete setup. Manual
  runs and the initial synchronization after selecting a pair remain available
  while automatic background sync is disabled.
- Coalesce notifications and avoid treating the app's own B writes as A
  changes. Checks operate on source data, even if the provider reports
  notifications for other accounts.
- Use durable state so interruption, process death, restart, or a retry does
  not duplicate contacts or lose pending decisions. A retry must validate
  what actually happened before repeating a potentially successful write.
- Re-read affected data before destructive operations or applying decisions,
  so a stale snapshot cannot silently overwrite a newer user or account-sync
  change. If the state has changed, compare again or request review.
- Treat provider errors, permission revocation, storage failures, read-only
  destinations, partial operations, and missing accounts as failures, not
  successful synchronizations. Keep recoverable work pending and surface
  errors in the UI and log when logging is available.
- Log source observations, decisions, intended and completed B actions, and
  failures as specified in [Android and data design](android-design.md).
  Preserve enough durable action state to recover from interruption between a
  provider operation and its outcome being logged.

Polling observes current state, not an immutable change feed: if a contact is
changed several times between checks, only the net change may be detected.
Sync is attempted when Android allows execution; it cannot promise an exact
deadline. In particular, a system force-stop prevents execution, including
automatic boot recovery, until the user explicitly launches the app again.
Device-specific background restrictions can also defer or prevent work.
