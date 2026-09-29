# Weave Phase 6.10.7 — Paged Group Pulse Store Fix

## Problem reproduced from diagnostics

Image posts could be durably created and accepted by Groups v2, then the legacy single-value Group Pulse rewrite failed with:

`app_service_error: store value is 33759 bytes; maximum is 32768`

The Pulse copied inline Base64 thumbnails and was stored as one DHT value. Retrying after the UI reported a failure could therefore create a second durable post/event.

## Changes

- Added an optional `pulse_root` to `GroupBranchHeader` while retaining v2/header compatibility.
- New creator and claimer branches create a dedicated 32-subkey Pulse DHT.
- Existing branches continue reading legacy branch subkey 1 until their owner next publishes a Pulse; that publish lazily creates/populates the new Pulse DHT and then advertises `pulse_root`.
- Pulse serialization is paged by actual encoded byte size, with a 30 KiB per-page ceiling rather than relying on conversation count alone.
- Pulse readers merge all pages for the same group/generation into the existing in-memory `GroupPulse` shape, so screens do not need a pagination rewrite.
- Inline Pulse thumbnails are capped at 20,000 Base64 characters. The immutable full message and full image blob remain unchanged and addressable.
- Newly selected group-post thumbnails are generated at a smaller preview size first, with a smaller fallback; if they are still unusually large, only the inline Pulse thumbnail is omitted.
- Once the immutable post, signed Groups-v2 event, and outbound transport already exist, a local branch-view update failure is logged as deferred instead of surfacing as total post-creation failure. This reduces accidental duplicate retry posts.
- Added Pulse publication diagnostics: generation, page count, total encoded bytes, and largest page size.

## Compatibility

- Old branch headers without `pulse_root` remain readable.
- Old single-value Pulses remain readable.
- Migration is lazy and owner-driven; no destructive storage reset is required.

## Validation performed here

- Inspected all modified call paths and constructor sites.
- Ran Kotlin parser/compiler front-end over the modified sources; expected unresolved Android/project symbols occur without the Android/Gradle classpath, but no parser/syntax diagnostics were reported.
- Full Gradle compile could not be run in this environment because the wrapper distribution is not cached and outbound access to `services.gradle.org` is unavailable.

## Suggested device test

1. Open the existing group that previously failed (`green things`).
2. Submit an image post comparable to `slowly dying pepper`.
3. Confirm the log reports `groups: Pulse published ... pages=... max_page=...` and no `store value ... maximum is 32768` error.
4. Refresh/re-enter the group and verify the post remains visible.
5. Submit enough image posts to force `pages=2` or greater and verify another device can refresh the branch and see the merged list.
6. Verify no duplicate is created if a transient local branch-view update error occurs after the signed event has already been created.
