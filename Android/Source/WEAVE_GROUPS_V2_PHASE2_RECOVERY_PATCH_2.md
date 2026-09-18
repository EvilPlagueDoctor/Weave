# Weave Groups v2 — Phase 2 Recovery Patch 2

Version: `0.6.2-groups-v2-phase2-recovery-patch`

This patch is based on Phase 2 Witness Patch 1 and addresses the receive-side DHT validation stall observed during Test 1B.

## Problem observed

A remote authority successfully received event `1bb0c3d7` through `PrivateMailbox`, verified its signature, and committed it to the encrypted Groups-v2 event store. The subsequent author-DHT read started but never completed. After Weave restarted, the durable event was restored but remained `content=Unchecked` / branch `Pending` because there was no automatic recovery pass.

## Changes

- Remote Groups-v2 event ingestion now persists first and queues content validation asynchronously.
- Author-DHT content fetches have a 25-second app-side ceiling.
- Timeout / transient misses are recorded as `Unavailable` (unless the exact content had previously verified `Valid`).
- Retry metadata is persisted with each event:
  - retry count
  - next retry timestamp
- Retry backoff: 15 seconds, 60 seconds, 5 minutes, 15 minutes, then 60 minutes.
- Startup scans valid non-expired events and resumes events with unresolved content or unresolved locally owned branch decisions.
- Recovery is queued before slower group-directory/profile DHT startup work.
- Duplicate transport arrivals do not start duplicate content jobs for the same Event ID.
- A successful retry logs `CONTENT_RECOVERY_SUCCESS`.
- Runtime recovery/fetch workers are explicitly stopped on daemon reconnect/account change to prevent an old session mutating a newly bound account's event store.
- Direct/Mailbox/PublicWitness transport formats are unchanged in this patch.

## New diagnostics

Expected lines include:

- `CONTENT_RECOVERY_SCAN candidates=N`
- `CONTENT_FETCH_QUEUED ... reason=arrival|startup|retry`
- `CONTENT_FETCH_BEGIN ... timeout=25000ms retry=N`
- `CONTENT_FETCH_TIMEOUT ...`
- `CONTENT_UNAVAILABLE ... retry=N next_in=...`
- `CONTENT_RECOVERY_SUCCESS ...`
- `CONTENT_FETCH_ALREADY_QUEUED ...`
- `CONTENT_RECOVERY_STOP ...`

The event summary also displays retry count and time to next retry when applicable.

## Test target

Shelly's previously stuck `1bb0c3d7` is especially useful after installing this build. Because older stored events have no retry metadata, startup should treat its retry time as immediate and attempt the DHT read automatically. If the payload is available, it should become `content=Valid` and the locally owned branch should continue to its normal policy decision. If unavailable, it should become retryable rather than hanging.
