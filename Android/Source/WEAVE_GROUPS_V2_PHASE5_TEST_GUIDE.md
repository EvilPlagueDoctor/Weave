# Weave Groups v2 — Phase 5 Test Guide

Use three phones where practical: A = author, B = authority/moderator, C = ordinary joined custodian.

## Regression tests

1. **Normal post delivery** — create a public-group post with ordinary mode. Confirm B can receive/accept it through Direct and that mailbox/witness duplicates dedupe.
2. **Custody** — confirm an ordinary joined C can retain a canonical ref and A can receive a verified receipt.
3. **Missed-event recovery** — take B offline, create a post, verify C holds it, then bring B online. Confirm `CustodyRecovery` can insert/validate/accept the missed event.
4. **Claim continuity** — verify the 24h quiet note and 48h+activity Claim-continuity behavior remains unchanged. Offline absence must not create reputation penalties.

## Hardening tests

### A. Oversized/malformed input
Feed malformed or oversized Groups-v2 packets through a developer harness if available. Expected behavior: early reject, no DHT fetch, no crash.

Useful logs include `EVENT_PROCESS_REJECT`, `CUSTODY_REJECT ... reason=structure-*`, or codec rejection before the runtime.

### B. Rate limiting
Repeatedly send more than the configured action limit from one peer/group. Expected:
- excess work is dropped;
- `ABUSE_RATE_LIMIT` appears at first/power-of-two rejections;
- line includes `reputation_penalty=false`;
- no group-reputation negative evidence is created merely for the rate limit.

### C. Custody quota behavior
Fill a test custodian to a deliberately lowered development quota or use a fixture. Expected:
- existing retained events remain;
- new excess event logs `CUSTODY_REJECT ... reason=*-cap`;
- no unrelated valid custody entry is evicted to make room.

### D. Recovery pagination
Use enough retained events for multiple pages. Expected:
- each page advances cursor;
- no more than 8 pages / 96 accepted events from a single custodian/session;
- repeated cursor terminates with `RECOVERY_PAGINATION_STOP ... reason=cursor-loop`;
- cap terminates with `reason=session-cap`.

### E. Recovery sweep bounds
With many known peers/groups, inspect diagnostics/logs. One sweep must target at most:
- 6 groups;
- 4 custodians per group;
- 24 total requests.
Verified live receipt custodians should be ordered before fallback peers.

### F. Worker saturation
With a development harness, overfill the custody work queue. Expected `CUSTODY_WORK_REJECT ... reputation_penalty=false`, no process crash and no unbounded queue growth.

### G. Restart/persistence
Restart Weave/daemon and confirm canonical events, custody refs, receipts, branch decisions, Phase-4 reputation and continuity state still load correctly. Rate-limit buckets intentionally reset on app restart.

## Production-cleanup checks

Settings should no longer expose:
- copy last Groups-v2 event;
- re-ingest last event;
- create conflict test;
- import Groups-v2 event from clipboard;
- custody-only test mode/test target/status fields.

Diagnostics remain available and should include an abuse-guard summary under the Groups-v2 reputation/continuity diagnostics.
