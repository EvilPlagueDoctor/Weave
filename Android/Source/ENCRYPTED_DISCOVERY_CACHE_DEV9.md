# Weave dev9: Encrypted local discovery snapshot

Based on dev8-fix2. Version: `0.12.0-dev9-encrypted-discovery-cache` / versionCode 54.

## What changed

- The existing daemon-owned encrypted `PrivateVault` stores `discovery/cache-v1/last-good` as a versioned, compressed named blob. It is **account scoped**, checks the unlocked account's main DHT ID, and stores at most 280 profile name/search hints, 48 verified profile documents and their metadata. No downloaded media/image bytes are placed in this snapshot; VSPF documents contain media *references*. Full VSPF documents are restored only if their size and SHA-256 still match the cached signed/validated profile record, and VSPF decoding/validation succeeds. The snapshot is last-known-good UI data, not proof that a peer is online.
- The snapshot is restored as soon as the embedded daemon vault is available and account identity is determined, **before** network profile discovery. The already-encrypted GroupStore data is loaded by its existing vault lifecycle and is never replaced with an empty directory merely because the DHT lookup failed.
- Daemon peer-list polling changed from 20 seconds to 2 minutes. At most 12 due profile verifications are launched per poll and at most 3 verify/download operations run simultaneously. Newly discovered root lookups are limited to 4 per poll. Existing snapshots remain visible when a fetch fails.
- Saved, unchanged profiles receive a lightweight profile subkey check every ~6 hours in the background, or ~5 minutes when viewed. A generation or `updatedAt` hint change can trigger sooner. When the SHA-256 and byte length match a cached VSPF document, no blob download occurs. Failures have a 2-minute retry delay. Active group directory checks use ~5 minutes; other checks ~1 hour and only happen when the profile is verified or specifically requested. Gossip-identified newer group branch generations still trigger the existing verification path.
- Group-directory read errors now **propagate**, rather than being converted to a successful empty-directory lookup. Successfully received but unchanged directory payloads no longer trigger an encrypted GroupStore rewrite.
- Snapshot writes use a 15-second coalescing delay on an IO coroutine and an account/vault generation guard; the daemon performs the actual encryption and atomic named-blob pointer swap. No account databases, key files or content DHT schemas were changed.

## Diagnostic markers

`discovery-cache restored hints=... documents=... encrypted=true`

`discovery-cache saved hints=... documents=... encrypted=true`

`app peers: ... profiles_scheduled=...`

`group directory checked ...` and `group directory refresh ... failed; keeping cached data`

## Test with two existing backed-up accounts

1. Install as a normal signed update. **Do not uninstall or clear app data.** Existing data is not migrated or deleted by this patch.
2. Start Weave and open 2-3 different remote profiles and groups. Let the daemon run at least 20-30 seconds after successful retrieval so the batched encrypted snapshot is written.
3. Force stop and restart. After unlock, known names and recently cached VSPF layouts should appear without waiting for network downloads. Groups/branch choices are restored by the existing encrypted GroupStore.
4. Temporarily disable network and restart: cached names, groups and eligible profile layouts should remain visible after vault unlock. DHT errors must **not** wipe group advertisements.
5. Switch to another daemon account and verify that the first account's names/layouts are not visible. Switch back to confirm they return.
6. Change a profile on the other phone and either wait for the active check interval or use a manual Refresh; ensure updated name/layout is verified before replacing the saved version.
7. Read log lines starting `discovery-cache`, `app peers`, `group directory` and verify that repeated refreshes don't redownload unchanged VSPF blobs.

## Limitations

- The cache is available only *after* unlocking the encrypted daemon vault; it is not an independent pre-login offline database.
- The snapshot is bounded and can omit very large profile layouts, keeping their names and references instead.
- A null/failed profile DHT lookup is treated as unavailable, **not** as confirmed unpublication. Correctly proving unpublication requires a separate signed withdrawal mechanism.
- Network presence/handshakes, mailboxes, group custody and existing gossip still operate independently. This patch doesn't fix all mailbox head-of-line stalls or authenticated delivery acknowledgments.
- **No Android/Rust build was run in this environment.** The code should be compiled using JDK 17, SDK/NDK 36 and the project's existing build script before treating this as tested.
