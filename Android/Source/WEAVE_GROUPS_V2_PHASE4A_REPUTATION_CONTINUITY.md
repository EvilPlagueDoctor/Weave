# Weave Groups v2 Phase 4A — Reputation, Authority Presence, and Claim Continuity

Date: 2026-09-15
Version: `0.8.0-groups-v2-phase4a-reputation-continuity` (versionCode 23)
Base: production Phase 3A custody/recovery build, not the temporary custody-test builds.

## Cleanup carried forward

The temporary custody-only switch, explicit test target, and last-test-line Settings fields are not present in this tree. Phase 3A's proven production custody/recovery code remains.

Recovery now prefers peers that have already issued a verified, unexpired custody receipt for the group. Other eligible peers remain bounded fallback candidates. This reduces broad recovery fan-out without making a receipt a guarantee of availability.

## Group-protocol reputation

`GroupTrustContinuityStoreV2` is an encrypted, account-scoped local evidence store under:

`groups_v2/trust_continuity_v1`

It is intentionally separate from the daemon's network-wide reputation system. Phase 4A does not create or remove daemon bans.

Evidence is deduplicated so the same event cannot repeatedly farm score. Positive score is capped at a small ceiling; cryptographically contradictory evidence can quarantine a peer from future custody/recovery selection even if it previously accumulated positive evidence.

### Positive evidence

- `CustodyReceiptVerified`: a custody receipt signature and canonical digest match the locally created event.
- `RecoveryCanonicalVerified`: a signed recovery batch returned a canonical event that passes Groups-v2 event validation.
- `RecoveryContentVerified`: the recovered event subsequently fetched author-owned DHT content whose hash matches the signed event.
- `CustodyReceiptHonored`: when the local account both has a verified receipt and later receives matching valid recovery content from that custodian.
- `GossipDhtConfirmed`: a gossip hint led to an authoritative branch DHT read that verified successfully.
- `CrossTransportCanonicalMatch`: two or more source-bound paths (Direct, PrivateMailbox, PublicWitness) carried identical canonical event bytes from the claimed author Main DHT.

### Strong negative evidence

- `InvalidCustodyReceiptSignature`
- `InvalidRecoveryBatchSignature`
- `InvalidRecoveredEvent`
- `CustodyReceiptDigestContradiction` — quarantines the custodian from future group-protocol selection.
- `AuthenticatedEventEquivocation` — quarantines the author only when the contradictory bodies are network-source proven or the signing key is profile-bound.

Remote signing keys that are still `RemoteUnbound` do **not** create equivocation reputation solely from matching Ed25519 keys. The conflict remains visible in EventStore diagnostics, but reputation is withheld unless Main-DHT/source binding is independently established.

### Never negative reputation

The following remain explicitly non-punitive:

- peer offline;
- moderator not recently seen;
- route failure / `TryAgain` / timeout;
- unanswered custody or recovery request;
- expired local custody cache;
- DHT content temporarily unavailable;
- gossip verification unavailable because the DHT could not be read.

Availability is not honesty.

## How reputation affects Phase 4A behavior

Reputation is deliberately narrow:

1. quarantined peers are not selected as custody/recovery candidates;
2. otherwise, higher local group-protocol reputation is a soft preference when selecting among candidates;
3. verified receipt custodians are preferred first during recovery;
4. reputation does not revoke membership, hide posts, revoke Claims, or create a network-wide ban.

## Authority presence

Presence is stored separately from reputation.

Live authority signals currently include:

- receiving group gossip from that peer;
- an authenticated Direct Groups-v2 event from that peer when it owns a known branch;
- authenticated custody-protocol traffic from that peer when it owns a known branch;
- authenticated private authority events from a known branch authority.

DHT readability alone is **not** treated as proof that an authority is online because old DHT state can remain readable after the owner disappears.

Presence writes are rate-limited to minute granularity because the UI only makes 24/48-hour decisions.

A branch's authority set includes its owner and known delegated moderators. If any authorized moderator is demonstrably active, the branch is not treated as abandoned merely because the owner is absent.

## Claim-continuity UI

For a selected moderation branch not owned by the local user:

- **< 24h without a live authority signal:** no warning.
- **~24h+:** a quiet note says the authority has not been seen recently and explicitly says this may be temporary. No reputation penalty is generated.
- **~48h+ plus pending or recent group activity:** a prominent `Moderation continuity` card appears with `Create independent Claim`.

The header also exposes `Claim moderation` for claimable public/unlisted groups so users do not have to wait for the continuity warning to create an alternative branch.

Claim creation always displays a confirmation explaining:

- the Claim is an independent moderation branch;
- it does not replace or revoke Original;
- users may choose which moderation branch to view.

Member-only groups remain non-claimable.

## Diagnostics

`Copy Log` / the diagnostic report now contains:

`--- Groups v2 Reputation + Authority Continuity ---`

with peer score, positive/negative evidence counts, strong-negative count, quarantine state, and last live authority signal.

Useful log lines include:

- `REPUTATION_EVIDENCE`
- `REPUTATION_WITHHELD`
- existing `CUSTODY_RECEIPT`, `RECOVERY_EVENT`, `CONTENT_HASH_VALID`

## Deliberately deferred

- No automatic daemon reputation submission from Weave.
- No negative gossip-poisoning score when DHT verification merely fails/unavailable; the verifier needs a distinct "read succeeded but content contradicted the hint" result before that is safe.
- No automatic Claim creation.
- No automatic Original revocation or branch takeover.
- No historical/archival guarantee beyond the existing short-term custody design.
