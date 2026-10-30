# Weave Messaging Diagnostics — Phase 1

This patch adds **logging only** on top of `Weave-Android-Messaging-Conservative-Phase1`. It does not alter packet formats, network retries, DHT schemas, mailbox scheduling, or verification outcomes.

## Correlation

Use the first 8 characters of the Groups-v2 event ID to correlate the Weave logs. `TRANSPORT_*` and `CUSTODY_*` timings use milliseconds. The daemon's local API `request`/`operation` identifier correlates its `mailbox_commit_started`, `mailbox_commit_finished`, or `mailbox_commit_failed` lines with existing `direct_*` and backlog records; the Weave RPC log records the corresponding API action. The daemon does **not** see the plaintext group-event ID in a generalized encrypted mailbox request, so the two IDs are not interchangeable. Correlate by the send time, target, request order, and Weave RPC timing when necessary.

## New Weave milestones

- `TRANSPORT_FAST_PATH_START`, `TRANSPORT_MAILBOX_COPY_START`, `TRANSPORT_PUBLIC_WITNESS_START`: actual worker start and pool queue wait.
- Existing `TRANSPORT_*_QUEUED` / `TRANSPORT_*_FAILED`: transport API completion, **not** proof of remote receipt.
- `CUSTODY_SELECTED`: candidate and verified group-peer counts.
- `CUSTODY_CAPACITY_LOW`: fewer than two initial eligible peers.
- `CUSTODY_STORE_START`: pool queue wait.
- Existing `CUSTODY_STORE_QUEUED` / `CUSTODY_STORE_SEND_FAILED`: include execution duration.
- `CUSTODY_STORED`: receiver accepted the event into its local custody store.
- `CUSTODY_STORE_SKIPPED`: receiver did not store it (the local store returned null; duplicate and failure are not distinguished).
- `CUSTODY_RECEIPT_VERIFIED`: signature and digest checked, local receipt recorded; includes valid receipt count.
- `--- recent messaging milestones ---` at the start of the copied diagnostics, so log truncation does not hide relevant events.

## Daemon

- `mailbox_commit_started` / `mailbox_commit_finished` / `mailbox_commit_failed` with timing and local request ID. Note that a caller timeout cancels the API operation, so `mailbox_commit_started` without a completion may accompany a `request_timeout`; that does **not** establish the message was delivered or lost.
- Removed the full binary handshake route blob dump and one redundant per-check-in log.

## Lower-volume gossip logging

Expected gossip failures to stale/offline peers are counted as `missing_route`, `offline`, `timeout`, or `other`, with periodic summary lines. Message contents are not recorded. A few first-window failures may be summarized immediately; later errors are grouped into one message per roughly 60s of continuing failures.

## Suggested two-device test

1. On device A, post one short public-group comment. Record the clock time and copy its Weave log.
2. On device B (group member, not the owner), keep Weave open, copy Weave and daemon logs after 60–90 seconds.
3. Turn the original moderator device off and repeat; look for `CUSTODY_STORED` / `CUSTODY_RECEIPT_VERIFIED`, not merely `*_QUEUED`.
4. Save a log after ~3 minutes if a send appears stuck: the daemon should show `mailbox_commit_started` plus either a completion/failure or the normal 150-second timeout.

Avoid sharing full raw logs publicly: request IDs and shortened peer identifiers are safe-ish for debugging but full daemon logs may include private system/network metadata.

Full Android/Rust build is not verified in this environment.
