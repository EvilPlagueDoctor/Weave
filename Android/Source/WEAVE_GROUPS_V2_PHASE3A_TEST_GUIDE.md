# Weave Groups v2 — Phase 3A Custody/Recovery Test Guide

Use three phones if possible:

- **Phone A — Author**: member of a public test group; posts events.
- **Phone B — Authority**: Original or Claim owner for that group.
- **Phone C — Ordinary member/custodian**: joined to the same group, no moderation authority.

A fourth non-member phone is useful for rejection/privacy tests.

## Test 1 — Custody acceptance and receipt

1. Keep A, B and C online with Weave open long enough for relevant peers to be discovered.
2. A creates a normal Groups-v2 post in a **Public** group.
3. Copy diagnostics from A and C.

Expected on A:

- `CUSTODY_SELECTED event=... candidates=...`
- one or more `CUSTODY_STORE_SENT`
- later `CUSTODY_RECEIPT ... verified=true` if C was selected and accepted

Expected on C if selected:

- `CUSTODY_STORE event=...`
- `CUSTODY_RECEIPT_SENT event=...`

C's diagnostics should show a retained custody event but C must have no new group moderation responsibility and must not auto-accept the post merely because it is a custodian.

## Test 2 — Non-member rejection

If a fourth phone D is available, leave it outside the group while keeping it visible as a relevant Weave peer.

When D receives a sampled custody request for the public group it should log:

`CUSTODY_REJECT ... reason=not-local-member`

It should issue no receipt and retain no custody event.

This is expected; candidate sampling deliberately does not expose a public membership directory.

## Test 3 — Encrypted persistence

After C has accepted at least one custody event:

1. fully close/restart Weave on C
2. keep the same VeilKnit account
3. generate diagnostics

Expected custody counts survive restart via the daemon-owned PrivateVault.

Switching daemon accounts should detach/clear the in-memory custody state and load the other account's encrypted custody state instead.

## Test 4 — Offline authority recovery

This is the important Phase 3 test.

1. Ensure C is a joined ordinary member and has recently been discoverable to A.
2. Close Weave on B (authority).
3. A creates one or more public-group posts.
4. Confirm C accepted at least one event into custody and A received a receipt if possible.
5. Reopen B.
6. Let B refresh peers and run recovery.

Expected on B:

- `RECOVERY_BEGIN`
- `RECOVERY_SOURCE request=... custodian=...`
- `RECOVERY_EVENT ... accepted=...`
- `EVENT_INGEST_BEGIN ... transport=CustodyRecovery`
- normal event signature validation
- normal `CONTENT_FETCH_BEGIN` against the **author's** DHT
- `CONTENT_HASH_VALID`
- one branch decision, typically `Pending->Accepted` for Everyone policy
- `RECOVERY_COMPLETE`

The Event Store evidence should include `CustodyRecovery`, while the event author remains Phone A—not Phone C.

Mailbox/PublicWitness may also recover the same event. That is fine: there must still be only one canonical stored event and one terminal branch decision.

## Test 5 — Pagination

To exercise continuation, arrange for C to retain more than 12 events in the same public group before B's recovery.

On recovery, expect multiple request/batch cycles. Each batch must be <=12 events and the second request should carry a non-empty continuation cursor.

All events should dedupe normally if another transport already delivered some of them.

## Test 6 — Branch-decision mirror

After recovered/direct/mailbox events are accepted or rejected, diagnostics should report branch-decision records in the new section:

`--- Groups v2 Custody + Branch Decisions ---`

The canonical Event Store remains authoritative for current operation, but the mirrored table should track one record per `(group, branch, event)` and should not create duplicate records when the same event arrives through multiple transports.

## Test 7 — Non-public privacy guard

Create or use an `Unlisted` or `MembersOnly` group and submit a Groups-v2 event.

Expected:

`CUSTODY_SKIP ... reason=nonpublic-membership-not-addressable`

Automatic recovery should likewise log:

`CUSTODY_SKIP ... reason=nonpublic-recovery-not-addressable`

No group ID should be sent to arbitrary sampled peers by Phase 3A custody/recovery for non-public groups.

## What is not a failure

- a sampled peer is not a member and therefore sends no receipt
- fewer than 12 custody peers are available
- a custodian goes offline later
- mailbox/public witness delivers the event before custody recovery
- the same canonical event is returned by multiple custodians

Those cases should be handled by bounded selection and EventStore deduplication.

## What would be a bug

- custodian receives moderation authority merely by storing an event
- recovered event's author becomes the custodian
- same event creates multiple posts/Pulse increments/terminal decisions
- non-public group IDs are sent to unrelated sampled peers
- same Event ID/different digest overwrites the custody copy
- recovery batch exceeds daemon message size
- event bypasses signature or DHT content-hash verification after custody recovery
