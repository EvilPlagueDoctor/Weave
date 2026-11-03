# Weave 0.12.0-dev7 — Handshake cooldown and mailbox batching

Base: 0.12.0-dev6-event-monitor (code 51). New build: **code 52**.
This is an **experimental** change to mailbox storage scheduling, not a migration.
The message wire format, group moderation, widget 1-hour TTL, and group witness
7-day TTL are unchanged.

## Changes

### Handshake
- A maintained outgoing handshake reuses an established session (and promotes a
  temporary established session to maintained status), or reuses the in-flight
  handshake without replacing its pending ephemeral keys or retry state.
- Completed ordinary outbound failures enter a **5-minute per-peer cooldown**;
  new outgoing attempts skip the direct path during cooldown and fall back to
  mailbox. Incoming handshakes remain accepted and a successful connection
  clears cooldown. Keep all existing 3 retries and early mailbox fallback.
- Local API releases the shared handshake-manager mutex **before the encrypted
  direct message is transmitted**. Tick/initial-handshake operations still have
  network awaits while holding that mutex; `[handshake-lock]` identifies these
  hotspots for later safe refactoring.

### Mailbox
- Up to **6 adjacent outgoing commands** of the same type coalesce inside a
  **150 ms** window and share a COW flush, instead of one full generation for
  each ordinary message or public service request.
- Batch types: encrypted outgoing message and service/witness request. Does
  **not** batch different command types or reorder barriers, and maintains one
  serialized COW writer; no simultaneous writes to the same DHT generation.
- Callers that cancel **before preparation** do not create a new message ID.
  Prepared messages finish or reconcile their commit even if a caller has
  timed out. Local encrypted outgoing-message state is persisted prior to flush.
- No shortcut to full-generation verification or index readback: the earlier
  COW integrity errors require preserving those checks until isolated testing.
- A timed-out client can retry and generate a second mailbox envelope; the
  signed Weave event deduplicates downstream, but **transport-level** idempotency
  across timeout/retry is not yet provided. Do not interpret an API timeout as
  proof that an already-prepared message was not published.

### Diagnostics
- `[handshake-policy]`: reuse_established/reuse_inflight/start/cooldown_started/
  mailbox_cooldown decisions with peer and retry/cooldown values.
- `[handshake-lock]`: costly lock waits and tick-held duration.
- `[mailbox-batch]`: collected/skip/prepare/commit results, batch size, duration.
- `[mailbox-commit]`: per-store commit phases, persistence, advertisement.
- `[cow-timing]`: write/readback/index/whole-generation validation durations,
  generation numbers, counts, DHT concurrency; no message body or keys.

## Tests to run

1. Run release Android/Rust build. **No full build was available in the editing
   environment.** This must be compiled before deployment.
2. Preserve your verified account backups; **never uninstall / clear data**.
   Sign with the same existing certificate.
3. Two phones: send two group comments and a post within 150 ms where possible;
   inspect `[mailbox-batch]` for count >1 and compare with single-message baseline.
4. Send several messages to an offline peer. Expect one `decision=start`, then
   `decision=reuse_inflight`, then after 3 retries `cooldown_started`, and
   `decision=mailbox_cooldown` on later sends for 5 minutes.
5. Attempt an inbound handshake from that peer during cooldown. It should
   establish and clear cooldown.
6. Background/restore while a COW commit is in flight; verify old accounts,
   in-progress event recovery and correct A/B generation validation after restart.
7. Follow any slow commit's `store=` and `stage=` from start to end. The last
   emitted stage identifies where it stalled; match Weave's transport event IDs.

## Known limitations

- The actor is still a single serialized commit writer. Batching reduces work
  and the mutex split moves direct transmission outside the lock, but handshake
  initiation and tick currently still await some I/O under the global mutex.
- COW full-generation validation remains enabled for data integrity. Its cost
  is measurable now but unoptimized.
- Adjacent batching only: interleaved maintenance/other command types split
  batches. Requests can still exceed the 150s API timeout on a sick network.
