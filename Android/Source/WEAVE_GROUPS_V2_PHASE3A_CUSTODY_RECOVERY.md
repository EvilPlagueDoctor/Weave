# Weave Groups v2 — Phase 3A Custody, Recovery, and Branch Decisions

Version: `0.7.0-groups-v2-phase3a-custody-recovery`  
Date: 2026-09-13

## Purpose

Phase 3A adds a bounded short-term custody layer around the canonical Groups-v2 event. Ordinary joined members may retain the **signed event reference** for a public group so that a moderator/branch owner can recover missed submissions later. Custody does not copy post/media payloads, does not change the canonical event, and never grants moderation authority.

The existing rule remains:

> Authors own content. Moderators own branch decisions. Custodians preserve references. DHTs provide content.

## Wire protocol

Custody uses authenticated daemon `sendMessage` delivery (direct-preferred with daemon mailbox fallback). It does **not** use the 1024-byte ServiceRequest witness channel.

Four compact binary packets were added in `GroupCustodyWireV2.kt`:

| Magic | Object | Purpose |
|---|---|---|
| `WCS2` | `CustodyStoreRequestV2` | Ask another node to retain the exact canonical event |
| `WCR2` | `CustodyReceiptV2` | Signed acknowledgement of retained event/digest/expiry |
| `WRQ2` | `CustodyRecoveryRequestV2` | Authenticated bounded request for retained group events |
| `WRB2` | `CustodyRecoveryBatchV2` | Signed bounded/paginated batch of canonical events |

The embedded event uses the existing `GroupEventWitnessCodecV2`; custody does not define another semantic event representation.

Signing domains are separate:

- `weave/group-custody/store/v2`
- `weave/group-custody/receipt/v2`
- `weave/group-custody/recovery-request/v2`
- `weave/group-custody/recovery-batch/v2`

Representative focused-test sizes:

- Store request: ~652 bytes
- Receipt: ~298 bytes
- Recovery request: ~259 bytes
- 12-event recovery batch: ~5.1 KiB

Recovery batches are capped at 12 events and 7,500 encoded bytes, beneath the daemon application's 8 KiB message ceiling.

## Custody selection and privacy

Weave currently does not publicly advertise ordinary group membership. Phase 3A therefore avoids creating a membership leak.

For **public groups only**, the sender deterministically samples up to 12 currently relevant app peers, excluding itself and known moderation authorities. The receiver accepts custody only when its own encrypted local group state says it is joined to that group or owns a branch. Non-members simply decline and issue no receipt.

Automatic peer-sampled custody/recovery is intentionally skipped for `Unlisted` and `MembersOnly` groups until Weave has a membership-addressable peer set that can be selected without revealing a non-public group ID to unrelated peers.

Diagnostics use:

- `CUSTODY_SELECTED`
- `CUSTODY_SKIP`
- `CUSTODY_STORE_SENT`
- `CUSTODY_STORE_SEND_FAILED`
- `CUSTODY_REJECT`

## Encrypted local storage

`GroupCustodyStoreV2.kt` is an account-scoped `PrivateVault.Participant`. Its state is stored under:

`groups_v2/custody/state_v1`

The value contains logical sections for:

- retained canonical events (`CustodyEventRecordV2`)
- received receipts (`CustodyReceiptRecordV2`)
- branch-local recovery cursors (`CustodyRecoveryCursorV2`)
- recovery-request replay protection (`CustodyRequestReplayRecordV2`)
- branch decisions (`GroupBranchDecisionRecordV2`)

All state follows the daemon's active account/private-vault lifecycle and is cleared from memory on vault detach.

### Initial bounds

- retention: min(event expiry, approximately 7 days)
- maximum retained events per author per group: 100
- maximum retained events per group: 1,000
- maximum retained events total: 5,000
- recovery request replay cache: 6 hours
- recovery page: maximum 12 canonical events

Capacity eviction prefers the earliest-expiring/oldest retained references. No media or post body is copied into custody storage.

## Store request and receipt

A receiving node checks:

1. authenticated daemon source equals `requesterMainDht`
2. request signature is valid
3. request group matches embedded event group
4. local node is eligible for the group
5. embedded canonical event independently passes Groups-v2 signature validation
6. event is not expired and local custody caps permit retention

Same Event ID + same canonical digest updates/extends local custody evidence. Same Event ID + different digest is logged as `CUSTODY_CONFLICT` and never overwrites the stored event.

If accepted and requested, the custodian returns `CustodyReceiptV2` committing to:

- group ID
- Event ID
- canonical event digest
- custodian Main DHT
- actual first-stored time
- actual local retain-until time
- whether requested retention was accepted or locally capped

A receipt is evidence that custody was accepted; it is not a moderation decision or availability guarantee.

## Recovery

After peer refresh, `GroupRuntime.recoverCustodyV2()` starts recovery for each **public group branch owned by the local account**.

Each Phase 3A recovery pass asks for the bounded seven-day window and relies on EventStore deduplication. This is intentional: different custodians can hold disjoint subsets, so advancing one branch-global time watermark after hearing from only some custodians could permanently skip an older event held by a newly discovered custodian. The wire cursor is therefore used for pagination within one custodian response; the branch-local recovery record is retained for diagnostics/future per-custodian optimization but does not narrow the Phase 3A lower bound.

Up to 8 relevant peers per group are selected deterministically for recovery requests. Recovery is retried after peer refresh with a five-minute throttle, so a startup with an empty peer cache does not permanently miss custody recovery.

A custodian serves a request only when:

- authenticated source equals request requester
- request signature is valid
- request is fresh (within 15 minutes)
- local node is joined/authorized to serve that group
- requester is known locally as a group owner or branch owner
- request ID has not already been processed recently

Responses are paginated. A continuation request is sent when `moreAvailable=true`.

## Recovered-event trust boundary

A custodian is **not** treated as the author.

Recovered events are inserted through `GroupEventStoreV2.ingest()` with transport evidence `CustodyRecovery` and `sourcePeer` equal to the custodian. The canonical event's own `authorMainDht` remains authoritative for payload retrieval and semantic authorship.

The normal pipeline then runs:

1. canonical event signature verification
2. Event ID/digest dedupe or conflict handling
3. author-owned DHT payload fetch
4. signed content-hash verification
5. local branch processing
6. idempotent moderation decision

A recovery copy can therefore add evidence without bypassing any existing Groups-v2 validation.

## Branch decisions

Phase 3A adds a separate encrypted logical decision table keyed by:

`(group_id, branch_id, event_id)`

`GroupBranchDecisionRecordV2` records:

- current `GroupBranchEventStateV2`
- first seen / last updated
- terminal decision time
- deciding Main DHT for terminal states
- monotonically increasing local decision generation
- optional future signed decision bytes

Existing EventStore branch state remains the compatibility/operational path. `GroupRuntime` mirrors state changes into the decision table whenever Groups-v2 branch state is created or changed.

This preserves the architectural distinction:

- canonical event conflict = event/authorship evidence problem
- branch decision disagreement = moderation/branch-state problem

No signed branch-decision publication protocol is enabled in Phase 3A yet; the storage shape is prepared for it.

## Reputation

Phase 3A adds **no reputation penalties or rewards** for custody behavior.

In particular, there is no penalty for:

- not being selected
- declining custody
- going offline
- cache expiry
- not answering recovery
- route/DHT failure

Future reputation integration should only use cryptographically demonstrable behavior, such as a signed receipt committing to one digest followed by an authenticated contradictory response.

## Files added

- `app/src/main/java/app/weave/GroupCustodyWireV2.kt`
- `app/src/main/java/app/weave/GroupCustodyStoreV2.kt`

## Main files modified

- `GroupRuntime.kt`
- `GroupEventStoreV2.kt`
- `SocialNetworkController.kt`
- `app/build.gradle.kts`
- `README.md`

## Validation performed in the build environment

Focused Kotlin compilation/round-trip tests were used because the full Android Gradle toolchain is not bootstrapped in this environment.

Validated:

- all four compact custody packet types encode/decode round-trip
- maximum representative 12-event recovery batch remains below 8 KiB
- custody storage source compiles in isolation against actual Groups-v2 event/wire types with platform stubs
- recovery pagination over 20 retained events returns 12 then 8 with a stable continuation cursor
- source/static audit confirms recovery copies use `CustodyRecovery` evidence while content fetch continues to use `event.authorMainDht`

The user's normal Android/Gradle build remains the authoritative full-project compile test.
