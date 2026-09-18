> **Phase 6.10.2 startup/Studio load fix (2026-09-18):** established profiles no longer fall through to Create profile during a transient vault reconnect, local widget source compilation is removed from Canvas/main-thread rendering, Widget Studio now has explicit loading/progress UI for large widgets such as Chess, and high-frequency diagnostic-log mirroring is throttled to stop startup GC/recomposition churn. See `WEAVE_PHASE6_10_2_STARTUP_STUDIO_LOAD_FIX.md`.

> **Phase 6.10.1 Chess load fix (2026-09-17):** large built-in widgets such as the full Chess example are now compiled/verified and persisted off the Compose main thread, local template saves reuse the already verified program instead of recompiling immediately, the Widget Library loads in the background, and the built-in-template button has a stronger outline. See `WEAVE_PHASE6_10_1_CHESS_LOAD_FIX.md`.

> **Groups v2 Phase 4A reputation/continuity build (2026-09-15):** temporary custody-test controls are removed; Weave now keeps encrypted, deduplicated group-protocol reputation evidence, prefers verified receipt custodians during recovery, tracks live moderation-authority signals separately from reputation, and adds 24h/48h claim-continuity UI. Absence, route failures, unanswered recovery and cache expiry never count as misconduct. See `WEAVE_GROUPS_V2_PHASE4A_REPUTATION_CONTINUITY.md` and `WEAVE_GROUPS_V2_PHASE4A_TEST_GUIDE.md`.

> **Groups v2 Phase 3A custody/recovery build (2026-09-13):** public-group submissions can now be retained as signed canonical refs by ordinary joined members, authors collect signed custody receipts, returning authorities request bounded/paginated recovery batches, recovered events re-enter the normal signature/DHT/hash pipeline, and branch decisions are mirrored into a separate encrypted decision table. Non-public automatic custody/recovery remains disabled until membership-addressable peers can be selected without leaking group membership. See `WEAVE_GROUPS_V2_PHASE3A_CUSTODY_RECOVERY.md` and `WEAVE_GROUPS_V2_PHASE3A_TEST_GUIDE.md`.

> **Groups v2 Phase 2 recovery patch (2026-09-13):** remote signed events are now persisted before content work, author-DHT validation is asynchronous with a 25-second fetch ceiling, transient failures persist retry/backoff metadata, and unresolved events automatically resume after restart. See `WEAVE_GROUPS_V2_PHASE2_RECOVERY_PATCH_2.md`.

> **Groups v2 Phase 2 test build (2026-09-13):** canonical signed post/comment events now use direct-preferred authenticated delivery, an independent forced mailbox copy, and a public witness ServiceRequest. See `WEAVE_GROUPS_V2_PHASE2_TRANSPORTS_HARDENING.md` and `WEAVE_GROUPS_V2_PHASE2_TEST_GUIDE.md`.

# Android Kotlin build

Jetpack Compose implementation.

Build debug APK:

```
build_project.bat
```

or

```
./build_project.sh
```

Clean with `clean_project.bat` / `clean_project.sh`.

The app stores local profile drafts under its private `files/profiles` directory. `live_profile.txt` is only a local stand-in for a future published profile DHT.

## Startup crash diagnostic helper

If a device still exits immediately at launch, connect it with USB debugging enabled and run:

```
capture_startup_log.bat
```

This relaunches the package and writes `startup_logcat.txt` beside the script so the exact exception can be shared without manually filtering Logcat.

## Hotfix 4 editor notes

The Android editor now uses Background / Boxes / Foreground workspaces, animated box entry/exit, collapsible property groups, depth drag-reordering and precise numeric entry. All application/editor labels are Android string resources in `app/src/main/res/values/strings.xml`; later translations can use standard `values-<language>/strings.xml` folders without changing VSPF data.

## UI revamp (version 5)

This pass focuses on editor clarity rather than profile-format changes:

- The bottom edit/tool strip now spans the full window so the inspector cannot cover it.
- Toolbar actions use clearer glyph + text pairs (Select, Text, Box, Link, Button, Stamp, Media, Widget, Page).
- Background / Boxes / Contents modes are explicitly labelled instead of relying on terse abbreviations.
- The old flat "Page / box / item" selector is now a collapsible Pages & layers tree; each page is visually separated and its contents are indented beneath it.
- The inspector uses card-like collapsible sections and a clearer selected-item header.
- Save and Save As are full-width actions so narrow inspector widths do not truncate both into ambiguous labels.
- The canvas uses a subtle dotted workspace, elevated page surface and a dedicated page/mode header.
- The editor uses a light indigo/teal palette with stronger selected-state contrast.

The profile document codec/model is unchanged by this UI pass.

## Revamped 4 text editing

- In **Contents / Inside box** mode, double-tap or press-and-hold a text item to edit it directly on the page.
- The Inspector collapse control stays pinned at the top while the Inspector body scrolls.
- Text and button label fields use buffered editing so Compose recomposition cannot overwrite an in-progress edit.
- Text appearance includes Default font, size, bold, italic, underline, colour and alignment.
- Buttons include solid/fade background, border, text styling and a placeholder Button Image command.

## WidgetLab 6

The Android build includes Widget Studio. Widget packages are stored privately as source-first `*.widget.txt` files; published packages contain source/manifest/provenance only and never executable bytecode. Remote widgets remain click-to-load: opening a profile shows only an inert placeholder until the viewer taps it, at which point Weave fetches source, compiles locally, verifies the locally-created VWB5 bytecode, and then runs it.

Phase 6.6 adds **Public Widget Networking v1**. A widget must explicitly declare `online = public` before any networking action is accepted. The VM still has no raw socket, HTTP, arbitrary DHT, mailbox, filesystem, camera/mic, native-code, or OS API access. Instead Kotlin exposes a narrow host path with at most 32 declared inputs, at most 2 submitted inputs per network action, and optional inert UTF-8 text capped at 1 KiB. Participant Session DHTs rotate and are retained for one week before tombstoning; each published widget instance also has a separate long-lived publisher-owned Data DHT.

Media/live-stream widget support has been removed from the widget language and built-in template catalog for now.

Phase 6.7 adds the first **receive-side widget event language**. Online widgets can handle validated proposals with `on network.input(...)`, observe exact accepted history with `on network.committed(...)`, receive inert text, and react to invitation/session lifecycle events. The language now also has bounded local `state` and typed `if` / `else` expressions. `network.accept` acknowledges the exact event hash presented by Kotlin; malformed or undeclared remote values never reach the VM. The bundled Chess widget now exercises the real invite → accept → final-ack session path and acknowledged FROM/TO square events.

Phase 6.9 adds the **Chess foundations** language/runtime pass: bounded numeric arrays, small non-recursive helper functions, dynamic host-validated UI properties, player-slot/event-player metadata, grouped actions containing up to five input steps, and a commit/reveal shared-random capability with deterministic alternating rolls. The Chess template now demonstrates a 64-cell board array, helper functions, a two-step FROM→TO action, exact-action acknowledgement, and a fair two-sided toss for White/Black assignment. The compiled bytecode remains local-only; published widget packages are still source-only.

## Phase 6.10 full Chess widget

Phase 6.10 turns the bundled Chess example into a full 64-square source-authored Chess widget. Rules remain in ordinary sandboxed widget source rather than being hard-coded into Kotlin. The widget includes legal move enforcement, captures, castling, en passant, promotion, king-safety/check/checkmate/stalemate handling, resignation and draw handling, plus exact-hash grouped network moves and commit/reveal White/Black selection. Phase 6.10 uses local VWB5 bytecode; publication is still source-only and click-to-load. See `WEAVE_PHASE6_10_FULL_CHESS_WIDGET.md` and `WEAVE_PHASE6_10_STATIC_VALIDATION.txt`.

## Local Content Filtering (0.5.1)

Weave now has viewer-side **Content filtering** controls in Settings. Filters are local to the current account and do not report, delete, reject, or republish anyone's content. Sexual-image and aggressive/threat-text classifiers use optional local ONNX models; all filters default to Off.

Before building with the full classifiers, install the model assets with `download_content_filter_models.bat` (Windows) or `./download_content_filter_models.sh` (Linux/macOS). See `WEAVE_CONTENT_FILTERING_V1.md` for architecture, covered surfaces, model notes, and current limitations.

## Groups v2 Phase 1 development build

This tree includes the first Groups v2 rewrite layer: canonical daemon-signed submission events and an encrypted account-scoped `GroupEventStoreV2`. The current Groups v1 delivery path remains temporarily active as a carrier while the new store is tested. See `WEAVE_GROUPS_V2_PHASE1_EVENT_STORE.md` and `WEAVE_GROUPS_V2_PHASE1_TEST_GUIDE.md`.

## Groups v2 Phase 5 production hardening

This tree is versionCode 30 / `0.9.5-widget-receive-events-v1`.
See `WEAVE_GROUPS_V2_PHASE5_PRODUCTION_HARDENING.md` and `WEAVE_GROUPS_V2_PHASE5_TEST_GUIDE.md` for the final Groups-v2 scale/abuse hardening pass.

## Phase 6 UX + curation

Phase 6 adds compact narrow-screen actions, supplied icon-only People/Groups navigation, group thumbnails, the Curator view, optimistic local posts, branch-local post moderation controls, Suggested people, and a batched GroupStore directory/branch persistence cleanup. See `WEAVE_GROUPS_V2_PHASE6_UX_CURATION.md` for details.



## Phase 6.8 widget interaction update

Phase 6.8 raises the public-widget input declaration ceiling to 32 while preserving the strict two-input limit for any one network action. In a paired session, up to five input actions may now be outstanding before Weave waits for acknowledgements. This lets a turn-based widget submit a short multi-step move (for example chess FROM square then TO square) without requiring a network round trip between taps. Each action is still separately canonicalized, hashed, range-checked, and acknowledged by exact event hash.

Activated widgets now have a host-owned **X** control at the top-right. The widget source cannot hide or override it. Pressing X stops the local runtime/network host, clears pending invitation/action state, discards runtime state, and returns the page to the inert pre-load placeholder. Reopening the widget starts from its source-defined initial state. Already-published DHT history is not erased by closing a local runtime.
