# Weave Groups v2 — Phase 1: Canonical Event Format + Encrypted Event Store

## What this build changes

This is the first implementation step of the Groups v2 rewrite. The existing Groups v1 network-delivery path is temporarily left in place so the app remains usable while the new foundation is tested. Every newly created group post/comment now also creates a Groups v2 canonical event and commits it to a new encrypted, account-scoped event store.

There is deliberately **no migration of old Groups v1 pending-event state** into the new event store.

## Canonical event

A Groups v2 submission contains a small immutable signed reference to author-owned DHT content rather than embedding the whole post.

Fields implemented in Phase 1:

- protocol version (`2`)
- immutable UUID `event_id`
- event type (`PostSubmitted` or `CommentSubmitted`)
- group ID
- author Main DHT identity
- creation time
- logical expiry time (7 days by default)
- group-scoped or explicit-branch target
- payload object type / record key / subkey / logical object ID / parent object ID / schema version
- deterministic SHA-256 content hash
- daemon app-signing key generation
- daemon app-signing public key
- Ed25519 signature

The signed event uses one deterministic binary canonical encoding. JSON is used only as the persistence/debug wrapper; arbitrary JSON text is never what gets signed.

### Scaling note

Normal submissions use `target=Group` and therefore **do not enumerate every Claim branch inside the signed event**. This intentionally leaves room for later bounded-fanout routing when groups have hundreds or thousands of Claims. Local branch state is attached lazily and is not part of the immutable event body.

## Signing

Weave already requested the daemon's `SignAppData` capability but previously did not expose it through `DaemonClient`. Phase 1 adds wrappers for:

- `get_app_signing_identity`
- `sign_app_payload`
- `verify_app_signature`

The daemon holds the private Ed25519 key. The private key never enters Weave.

Local events are marked `LocalDaemonBound` after confirming the daemon signing identity belongs to the currently active Main DHT.

A copied event imported on another phone can already have its cryptographic signature verified. Until a later phase publishes/pins the app-signing public key in the user's profile protocol, a remote event is intentionally reported as `RemoteUnbound`: cryptographically valid, but not yet independently profile-bound to the claimed remote Main DHT.

## Author-owned payload DHT

The existing group message DHT is used as the Phase-1 author-owned payload store. After a post/comment is published, Groups v2 records its returned `WeaveObjectRef` as the payload reference.

Weave then:

1. deterministically hashes the exact `WeaveMessage` payload;
2. creates/signs the canonical event;
3. commits the event to encrypted storage;
4. reads the DHT payload back;
5. hashes it again;
6. records `Valid`, `HashMismatch`, or `Unavailable` content state.

## Encrypted GroupEventStoreV2

Private-vault key:

`groups_v2/event_store_v1`

The daemon's existing `PrivateVault` provides account scoping and encryption.

Every event-store mutation is committed synchronously before the operation returns. Weave therefore does not need a special graceful-shutdown procedure for Groups v2 event persistence. If `EVENT_STORE_COMMIT` was logged successfully, force-closing Weave should not remove that committed event.

Stored metadata includes:

- canonical signed event
- first/last seen timestamps
- signature validation state
- author-binding state
- content-validation state
- per-transport delivery evidence
- independent per-branch states
- conflicting same-ID event evidence

## Deduplication

`event_id` is the primary deduplication key.

Receiving the same canonical event again:

- does not create another event;
- updates transport/source evidence;
- increments the duplicate/evidence count;
- logs `EVENT_DUPLICATE`.

Receiving the same event ID with a different canonical signed body:

- never overwrites the stored canonical event;
- retains the conflicting signed event as evidence;
- logs `EVENT_CONFLICT`.

A temporary Settings button can deliberately create this equivocation case for testing.

## Branch decisions

One canonical event can have multiple independent local branch states:

- `Unseen`
- `Pending`
- `Accepted`
- `Rejected`
- `Ignored`

The branch states are **not** part of the immutable signed event.

During Phase 1, the selected branch and locally owned branches are attached lazily (with a safety cap). Existing `Everyone` auto-accept behavior marks a locally owned branch `Accepted` in the v2 event store while another branch can remain `Pending`.

## Logging

Groups v2 diagnostic entries are deliberately verbose and use a stable short Event ID on every related line.

Typical creation flow:

```text
[groups-v2] PAYLOAD_WRITTEN ...
[groups-v2] EVENT_CREATE_BEGIN event=...
[groups-v2] EVENT_SIGNED event=...
[groups-v2] EVENT_SIGNATURE_VALID event=...
[groups-v2] EVENT_STORE_COMMIT ...
[groups-v2] EVENT_STORE_INSERT event=...
[groups-v2] EVENT_CREATE_DONE event=...
[groups-v2] CONTENT_FETCH_BEGIN event=...
[groups-v2] CONTENT_HASH_VALID event=...
[groups-v2] EVENT_STORE_COMMIT ...
```

Startup/reconnect includes:

```text
[groups-v2] EVENT_STORE_LOAD restored=...
[groups-v2] EVENT_STORE_BOUND events=...
[groups-v2] EVENT_CLEANUP ...
```

The copied diagnostic report now includes a `Groups v2 Event Store` snapshot before the raw log.

The persistent Weave diagnostic history was increased from ~180 KiB to ~700 KiB (1 MiB rollover ceiling). This is intentionally large enough for lengthy multi-phone tests while staying below the size where copying multi-megabyte text through Android's clipboard/Binder becomes risky.

## Temporary Phase-1 Settings controls

Under Settings → Diagnostics:

- **Copy event** — copies the most recent canonical signed Groups v2 event.
- **Re-ingest** — feeds the exact same event back through the store to test deduplication.
- **Create conflict test** — creates a second correctly signed body with the same Event ID but a different content hash; the original event must remain canonical and the conflict must be retained separately.
- **Import event from clipboard** — imports an event copied from another phone, verifies its signature, fetches the author DHT payload, and verifies its content hash.

These are development controls and are expected to disappear or move behind a developer diagnostics mode later.

## What Phase 1 intentionally does NOT change

Phase 1 does not yet replace:

- direct group submission transport;
- private moderator mailbox delivery;
- public witness / ServiceRequest delivery;
- custody gossip;
- custody receipts;
- reputation scoring;
- authority-presence prompts;
- abandoned-group Claim suggestions.

Those later systems will all feed the same `GroupEventStoreV2.ingest()` entry point.

## Daemon requirement

This build requires a daemon with API v3 app-signing operations (`SignAppData`), such as the VeilKnit Daemon v11 update. An older daemon without `get_app_signing_identity`, `sign_app_payload`, and `verify_app_signature` cannot create Groups v2 events.
