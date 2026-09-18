# Groups v2 Phase 1 — Test Guide

## Before testing

Use the updated VeilKnit daemon with `SignAppData` support. For clean observations, create a fresh test group rather than relying on old Groups v1 pending items.

The current build still uses the old network submission carrier in parallel; the thing under test is the **new canonical event + encrypted event store**.

## Test A — one phone, normal creation

1. Open a group.
2. Create a simple text-only post.
3. Wait until Weave reports that submission finished.
4. Settings → Diagnostics → Copy log.

Expected log sequence for one Event ID:

- `PAYLOAD_WRITTEN`
- `EVENT_CREATE_BEGIN`
- `EVENT_SIGNED`
- `EVENT_SIGNATURE_VALID`
- `EVENT_STORE_INSERT`
- `CONTENT_FETCH_BEGIN`
- `CONTENT_HASH_VALID`

The diagnostic header should show `stored events: 1` (or one higher than before).

## Test B — duplicate handling

1. Settings → Diagnostics → **Re-ingest**.
2. Copy the log again.

Expected:

- `EVENT_DUPLICATE ... canonical_match=true`
- the event-store count does **not** increase.

Repeat several times if desired. The same event must remain one stored event.

## Test C — Weave process restart

1. Note the short Event ID in the copied diagnostics.
2. Force-close Weave normally through Android's app switcher / force stop.
3. Reopen Weave.
4. Copy diagnostics.

Expected:

- `EVENT_STORE_LOAD restored=...`
- the exact same Event ID appears in the store snapshot;
- no new Event ID is generated merely by reopening Weave.

There is no special Weave graceful-shutdown step to perform.

## Test D — daemon + Weave restart

1. Ensure the event is present.
2. Close Weave.
3. Stop the VeilKnit daemon safely.
4. Start the daemon and sign back into the same account.
5. Reopen Weave.
6. Copy diagnostics.

Expected: the same Event ID is restored from the daemon-owned encrypted private vault and remains signature/content valid.

## Test E — phone reboot

Reboot the phone, start the daemon, then Weave, and repeat the same check.

## Test F — conflict/equivocation protection

1. Make sure the most recent event belongs to this account.
2. Tap **Create conflict test**.
3. Copy diagnostics.

Expected:

- `DEBUG_CONFLICT_CREATE`
- `EVENT_CONFLICT`
- event-store count stays the same;
- `conflicts=1` (or higher) appears for the event;
- the original canonical event is not replaced.

## Test G — branch-state independence

A useful natural setup is a group where the phone owns a Claim but is viewing another branch.

Create a post in an `Everyone` group.

Expected Groups-v2 store behavior can include, for the same Event ID:

- the locally owned Claim branch → `Accepted`
- the selected non-owned branch → `Pending`

There must still be only one underlying event.

## Test H — two phones, portable event

Phone A:

1. Create a post.
2. Settings → Diagnostics → **Copy event**.
3. Transfer the copied text to Phone B (message to yourself, temporary note, etc.).

Phone B:

1. Copy the event text into the clipboard.
2. Settings → Diagnostics → **Import event from clipboard**.
3. Copy diagnostics.

Expected on B:

- `EVENT_INGEST_BEGIN ... transport=DebugImport`
- `EVENT_SIGNATURE_VALID`
- `EVENT_STORE_INSERT`
- `CONTENT_FETCH_BEGIN`
- normally `CONTENT_HASH_VALID`
- `author_binding=RemoteUnbound` in stored state until a later phase adds profile publication/pinning of the signing key.

If the author's DHT payload is temporarily unavailable, `CONTENT_UNAVAILABLE` is acceptable; importing the same event again later should remain a duplicate and retry content verification.

Import the same event a second time on B. The store count must not increase.

## Test I — three phones

Recommended roles:

- Phone A: poster
- Phone B: owner/claimer
- Phone C: spectator

For Phase 1, manually copy/import the same event onto B and C.

Then copy all three diagnostic reports and keep the short Event ID visible. The same immutable Event ID, canonical digest, signing key generation and content hash should line up across all phones.

This gives us a clean baseline before Phase 2 introduces automatic direct/private/public transports.

## What to send back after testing

The most useful report is:

1. poster log;
2. moderator/claimer log;
3. spectator log if available;
4. a short note saying which test steps you performed and anything visually odd you noticed.

The copied report now retains substantially more history than v12, so avoid clearing app data between the interesting action and copying the diagnostics.
