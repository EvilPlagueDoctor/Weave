# Weave dev11 — fast startup peer/profile discovery

Based on dev10 with the account-isolation fix and Veilid v0.5.8 dependency tag.
Android versionCode: 57; versionName: 0.12.0-dev11-fast-peer-discovery-veilid-0.5.8.

## Symptom and source of slowdown

Encrypted-discovery-cache dev9 changed the daemon app-peer poll from every 20s to every 120s,
limited each poll to 12 profile verifications, and limited concurrent checks to 3. New accounts
with empty caches, offline nodes and profiles without published records were starved.
Each pass could also block on up to 4 serial getAppRoot RPCs, and successful profile checks
performed group-directory DHT reads while holding profile verification slots.

## Adjustments

- Begin the first daemon app-peer discovery pass immediately after GroupRuntime and live
  subscriptions are initialized, before the potentially slow own-directory publication.
- Perform peer discovery every 15 seconds for at least 60 seconds; keep doing so until there
  are 60 cached profiles AND 60 discovered peers, or 4 minutes have elapsed. Afterward,
  drop to one daemon peer-list poll every 120 seconds.
- Schedule up to 24 *due* profile verifications per warmup pass (12 in background), with up
  to 4 simultaneously active. Unseen profiles and saved metadata missing a layout get priority over rechecking cached records.
- Continue to respect the six-hour background TTL for unchanged verified profiles, the
  five-minute active-view TTL and the two-minute failed-profile retry delay. Records with
  cached metadata but no verified layout are immediately due for a document download.
  Existing cache is shown on unlock before discovery.
- Inventory all discovered peers before selecting a profile batch. Resolve missing app roots
  on two strictly bounded background workers with a 45-second cooldown, instead of serial
  getAppRoot calls that block peer-list processing. Successful root results trigger a profile
  verification immediately.
- Start group-directory checks on separate coroutines after verified profile content is
  saved. Use a coroutine semaphore to run only 2 group-directory DHT reads at once; these
  no longer hold the 4 available profile verification slots or occupy waiting IO threads.
- Keep periodic group-custody recovery at its previous 120s cadence during warmup, rather
  than increasing it to every 15s.
- Preserve account identity/session guards and encrypted account-scoped snapshots. No DHT
  formats, account keys, profile publishing logic, or stored caches are migrated.

## Diagnostic markers

`app peers: cached=... returned=... profiles_scheduled=... roots_queued=... warmup=true search=...`

The daemon-peer count (`returned`) counts peer DHTs, not confirmed published profiles.
A `profile record not published or unavailable` failure is not proof of unpublication;
network read failures and unpublished profiles can both cause it.

## Safe manual test

1. Update over the existing install using the SAME signing key. Do not uninstall or clear data.
2. On an account with a sparse cache, record the count of visible profile names at 0, 15,
   30, 60, 120 and 240 seconds after a successful login. Compare with dev10.
3. Check that `warmup=true` appears, and `profiles_scheduled` is not hard-capped at 12.
   After the warmup the logs should show `warmup=false` and the 2-minute interval.
4. Open already cached profiles and groups offline. The old names/layouts should still load.
5. Switch between backed-up accounts; no data should cross between accounts and the
   initial discoveries should be scoped to the new main DHT.
6. Watch daemon mailbox latency: the 4 profile verification slots + 2 directory workers are
   intentionally bounded to avoid saturating mailbox writes.

## Build status

No Android/Rust build is possible in this environment. The ZIP has been checked for archive
integrity and the incremental patch for clean application against the dev10 source.
The earlier dev10 Veilid v0.5.8 source pin still requires Cargo.lock refresh as documented.
