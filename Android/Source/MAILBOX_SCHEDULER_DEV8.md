# Weave 0.12.0-dev8 — mailbox scheduler and COW readback diagnostics

**Base:** `0.12.0-dev7-mailbox-batching`, versionCode 52. **New:** versionCode 53.

Experimental source-only update. The application/message formats, account namespace, signing, handshake 3-retry and 5-minute failure cooldown, public-group witness 7-day maximum, and widget 1-hour maximum are unchanged.

## Changes

- Every mailbox actor command is timestamped at the channel sender. `[mailbox-queue] stage=dequeued` includes its actual actor-queue delay; `[mailbox-queue] stage=prepare_start` measures its delay before preparation. These expose wait time separately from network or COW commit time.
- The 150 ms outgoing collection window now batches **mixed adjacent** `SubmitOutgoingMessage` and `PublishServiceRequest` commands, up to 12. Both can share one serialized COW commit. Other commands are strict ordering barriers (particularly inbox operations, withdrawals, walk events, and shutdown); they are **not reordered** to force larger batches. Already-timed-out callers are skipped *before* preparation. Once prepared, work remains durable and commits even if the caller subsequently times out; no unsafe mid-write cancellation.
- Newly written COW pages are read back **in parallel** (up to 16), rather than N serial readbacks. All per-page hash and generation verification checks remain enabled. Per-subkey readback and generation-validation reads have 45-second deadlines and log the subkey on timeout; **writes are not cancelled** and instead warn if still waiting after 15 seconds.
- `[cow-subkey]` reports long writes/readbacks and subkeys that time out; `[cow-timing]` retains overall phase timing.
- During a queue of 16 or more operations, the actor sheds *best-effort* walk observations and defers periodic flush/maintenance. A 5-minute max deferral for periodic flush and 10-minute max for maintenance ensure they are not starved forever. User submissions, deletions, and shutdown are **not** dropped. Snapshots are refreshed at most every 250 ms for walk-only operations, and immediately for other commands.

## Known limits / decisions

- The actor still has **one writer** for COW consistency. Incoming `ReadInbox` marks messages read and persists the local encrypted store, so it is **not** reordered ahead of pending writes; this avoids out-of-order read/delete or discovery behavior. Queue metrics will tell us whether a future separate fast lane is justified.
- **Transport-level idempotency across API timeout and client retry is not complete.** A commit completed after caller timeout can still be retried as a second envelope, because API requests do not currently carry a durable client-defined idempotency key. Never infer idempotency from identical payloads: users can intentionally send the same message twice.
- If a DHT page read timed out after pages were written, the index is not advanced. The existing transaction journal and A/B generation recovery decide whether to retry safely. No account migration or data-reset step.
- The new 45-second per-subkey deadline covers **readback and generation-validation reads only**; other network operations may still take longer. The actor can still be occupied by one large COW commit, and inbox reads can still wait behind it.
- Rust/Android toolchains were not available in the packaging environment. This is **not compile-verified or device-tested**. Build and test on the usual Windows machine before relying on it.

## Suggested two-device test

1. Preserve account backups. Install an APK signed with the **existing** release key. Never uninstall or clear storage.
2. Trigger one post and comment in a public group while both phones are online; watch `[mailbox-scheduler] stage=batch_collected`, `[mailbox-queue]` and `[cow-timing]`.
3. Temporarily use an offline/unreachable peer, send several events and observe the already-existing `[handshake-policy] decision=mailbox_cooldown` without restart storms.
4. Inspect per-subkey `[cow-subkey]` logs if a write, readback or validation takes over three seconds (or times out). Confirm no new `StoreCorrupt`, page-range conflict or digest mismatch.
5. Compare one 150-second stalled `send_message` to the actor's queue-wait and batch commit durations. Verify the destination phone's event monitor receives one signed event regardless of duplicate transport sightings.
6. Restart cleanly after a batch and ensure persisted events and inbox entries still load correctly.

The primary improvement to expect is reduced long-tail mailbox latency from parallel readback and mixed batching, **not** instant guaranteed peer delivery or guaranteed 7-day network retention.
