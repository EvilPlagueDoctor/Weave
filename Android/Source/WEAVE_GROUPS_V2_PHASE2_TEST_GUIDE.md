# Weave Groups v2 — Phase 2 test guide

## Recommended three-phone setup

Use a group where the **poster is not the Original/Claim owner** so the direct and forced-mailbox paths really have a remote authority target.

- Phone A — ordinary poster
- Phone B — Original owner or Claim owner
- Phone C — ordinary spectator, no moderation responsibility

Keep all three daemons/Weave apps online for the first test.

## Test 1 — all online, text-only post

1. Open the same public/unlisted group on A/B/C.
2. On A, create one text-only post.
3. Wait roughly 30–60 seconds.
4. Copy diagnostics from all three phones.

### Expected on Phone A

Look for one event ID throughout:

- `PAYLOAD_WRITTEN`
- `EVENT_CREATE_BEGIN`
- `EVENT_SIGNED`
- `EVENT_STORE_INSERT`
- `CONTENT_HASH_VALID`
- `TRANSPORT_SCHEDULE`
- `TRANSPORT_FAST_PATH_QUEUED` for B (or a clearly logged failure)
- `TRANSPORT_MAILBOX_COPY_QUEUED` for B (or a clearly logged failure)
- `TRANSPORT_PUBLIC_WITNESS_QUEUED`

The post operation should not wait for the slow mailbox/public paths because transport work is queued on a bounded worker pool.

### Expected on Phone B

The same canonical event should arrive through one or more transports. Order is not guaranteed.

Typical sequence:

- `EVENT_INGEST_BEGIN ... transport=Direct`
- `EVENT_SIGNATURE_VALID`
- `CONTENT_HASH_VALID`
- one branch decision/auto-accept
- later `EVENT_DUPLICATE ... transport=PrivateMailbox`
- later `EVENT_DUPLICATE ... transport=PublicWitness`

The diagnostic event summary should remain **one event** while evidence grows, e.g.:

`evidence=Direct:1,PrivateMailbox:1,PublicWitness:1`

There must be only one effective branch write for the event. In particular, the same event must not advance Pulse generation twice.

If posting policy is Approval/Established, expect one pending moderation task rather than auto-acceptance.

### Expected on Phone C

Phone C should eventually learn the event from the public witness path:

- `EVENT_INGEST_BEGIN ... transport=PublicWitness`
- `EVENT_SIGNATURE_VALID`
- `CONTENT_HASH_VALID`
- `EVENT_STORED_SPECTATOR`

It should not make a branch decision.

## Test 2 — moderator temporarily offline

1. Close Weave on Phone B. Leaving the daemon running is fine for the first version of this test.
2. Keep C online.
3. A creates another text post.
4. Wait 2–5 minutes.
5. Reopen B and wait for its inbox/mailbox drain.
6. Copy diagnostics from A/B/C.

Expected:

- A preserves its local canonical event and attempts both fast and forced-mailbox delivery.
- C can observe the public witness while B is absent.
- B later receives the same event from mailbox/inbox, verifies signature and DHT hash, and processes it exactly once.
- The private copy's logical event expiry is ~7 days; the public witness TTL remains ~1 hour.

A failed direct-preferred call may itself fall back to the daemon mailbox, so B can legitimately see more than one `PrivateMailbox` observation. The event count must still stay one.

## Test 3 — public/private agreement

If B receives both `PublicWitness` and `PrivateMailbox` for the same event, confirm:

- one event ID;
- same canonical body;
- evidence contains both transports;
- no conflict is created.

This is the foundation for the later public-vs-private reputation/evidence comparison.

## Test 4 — authenticated conflict hardening

The existing **Create conflict test** deliberately creates a second body with the same event ID and validly re-signs it with the same local application signing key.

Expected now:

- `EVENT_SIGNATURE_VALID` for the alternate body;
- `EVENT_CONFLICT_AUTHENTICATED`;
- `class=AuthenticatedEquivocation`;
- canonical stored event count remains unchanged;
- diagnostics increment `authenticated-equivocations`.

Do not use this debug result as real reputation evidence; it is an intentional local test fixture.

## Opportunistic hardening observations

You do not need to deliberately recreate these failures.

### Reconnect backoff

If daemon authorization/installation/service failure happens naturally, copied logs should show increasing `reconnect backoff` delays instead of a permanent ~1.5-second retry storm.

### Gossip logging

Startup should no longer contain dozens of identical `gossip summary` lines in a fraction of a second. A changed digest can log immediately; an unchanged digest is normally suppressed for ~60 seconds per source.

### Reputation block

If a transport is rejected by daemon reputation policy, look for:

`REPUTATION_BLOCK_HINT event=... target=... transport=...`

Please include the surrounding daemon/Weave logs if it reappears.

## What to send back

For the most useful comparison, send one text file containing:

- Phone A diagnostics
- Phone B diagnostics
- Phone C diagnostics

with the phone roles noted. The shared eight-character event ID makes the three traces easy to line up.

## Witness Patch 1 note

Phase-2 Witness Patch 1 replaces the public-witness JSON wrapper with a compact binary codec after Test 1 exceeded the daemon ServiceRequest serialized limit (1061 bytes vs 1024 maximum). Look for `WITNESS_ENCODE` on the sender and `WITNESS_DECODE` on receivers. A normal public post witness should be comfortably under the local 700-byte payload guard.

## Recovery Patch 2 note

Phase-2 Recovery Patch 2 makes remote content validation durable and restart-safe. Incoming Direct/Mailbox/PublicWitness events are committed to the encrypted event store before their author-DHT payload is fetched. The fetch is then queued asynchronously with a 25-second app-side timeout.

Transient failures use persisted backoff: approximately 15 seconds, 60 seconds, 5 minutes, 15 minutes, then hourly. On startup, valid non-expired events with `content=Unchecked`/`Unavailable`, or unresolved locally owned branch decisions, are queued again automatically.

Useful diagnostics:

- `CONTENT_RECOVERY_SCAN candidates=...`
- `CONTENT_FETCH_QUEUED ... reason=startup|arrival|retry`
- `CONTENT_FETCH_BEGIN ... timeout=25000ms retry=...`
- `CONTENT_FETCH_TIMEOUT`
- `CONTENT_UNAVAILABLE ... retry=... next_in=...`
- `CONTENT_RECOVERY_SUCCESS`
- `CONTENT_FETCH_ALREADY_QUEUED`
- `CONTENT_FETCH_CANCELLED` during daemon/account teardown

For the first recovery test, Shelly's previously stuck event `1bb0c3d7` is useful. After installing/restarting this build, it should be picked up by the startup recovery scan without needing another mailbox delivery.
