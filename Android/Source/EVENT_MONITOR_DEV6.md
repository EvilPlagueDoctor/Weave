# Weave 0.12.0-dev6 — Event Monitor and Route Diagnostics

Built on **0.12.0-dev5-fix1**. App version code **51**. Both Android app Kotlin and embedded VeilKnit daemon Rust files are changed.

## Settings → Event Monitor

- **Group events**: account-scoped, encrypted, existing signed Groups-v2 store. Shows one row per canonical signed event ID, labels Group post / Group comment, incoming/outgoing, evidence transport and counts, duplicate sightings (total observations minus one), validation and conflicts. The latest 250 retained group events are visible; seven-day event expiration is unchanged.
- **Profile comments**: existing local encrypted comment store; labels Profile comment, state and origin. Duplicate comment IDs are already upserted into one record, but historical repeat-sighting counts are not persisted in this store; therefore the UI shows “sighting count unavailable,” not a fabricated zero.
- **Filters**: All / Posts / Comments and Both / Incoming / Outgoing. One list includes both group and profile comments.
- **Open item**: group record details show author binding, branch states, transport evidence, and (if signature and content hash were previously verified) asynchronously fetch the body only after selection. Buttons open the post or author. Profile comment detail displays locally stored body and navigates to its profile. The navigation for posts not available on the selected moderator branch may fall back to the group screen's existing behavior. No media is downloaded automatically by the monitor.
- **Transport calls**: last 120 RPC and route diagnostic lines, metadata only. These *are not* signed content events and are not merged into the deduplicated content list.
- **Resource controls**: polling only while monitor is open (1.5 seconds), off-main-thread snapshots, maximum 250 group events + 250 profile comments and 120 transport lines, no new network subscription, no new unencrypted event-content database.

## Route markers

- Weave `send_message` emits `[ROUTE] action=send_message path=direct_handshake|mailbox_fallback|mailbox_only|unknown ... outcome=queued|failed` using optional `delivery_path` metadata returned by the embedded daemon. “Queued” is not a remote receipt.
- Weave `send_gossip` emits slow (>2s) or failed `[ROUTE] ... path=gossip_datagram` markers. Gossip is a datagram, not a handshake or proof of delivery.
- VeilKnit emits `[delivery]` direct start/queued/fallback/mailbox commit timings (already present in dev5). New `[inbox-route]` markers separate the **membership list lookup** from the **local mailbox store read/delete**. If an operation times out during either awaited call, the final stage marker may be absent; correlate timestamps with `[RPC]` elapsed_ms and operation backlog.
- Slow (>2s) `read_public_store` and `read_blob_range` calls emit `[read-route]` entries specifying DHT refresh intent and duration. **The current Veilid API does not expose internal hop count or which individual network peers participated**; no path is fabricated.

## Quick two-phone test

1. Install the release-signed APK and verify Settings says `0.12.0-dev6-event-monitor (code 51)`.
2. Go to Settings → Event monitor on Phone 1. Post once from Phone 2 and watch for a Group post entry. Tap to view the verified content and open its post. Check the type label and original signed ID.
3. Deliver the same event again (witness/gossip/retry) and confirm there is still only one row with increasing evidence counts.
4. Open Transport calls, submit another post and compare `[ROUTE] path=...` with the daemon `[delivery]` timing log. Test with the moderator online and offline.
5. Add a profile comment and confirm Profile comment appears with correct status. Open its details and profile.
6. Keep Weave in the background for a while; check that no content is lost and monitor does not affect the daemon's lifecycle.

## Limitations

- Only Groups-v2 and local profile-comment stores currently expose stable content/event IDs to this UI. Widget traffic, profile-publish transactions and arbitrary daemon messages appear as **transport metadata**, not full clickable semantic entries.
- Full hop-by-hop Veilid routing, intermediate nodes, and precise source of a queued mailbox request require instrumenting Veilid's lower transport/walk layers; they are not claimed by this patch.
- The UI is intended as a developer diagnostics surface and is **not** a real-time audit feed with network-wide visibility. A phone can only show events it has encountered or retained.
- This build has not been compiled or tested on Android in the packaging environment; run local/CI Gradle and Rust builds before distributing.
