# Weave messaging reliability — conservative first pass (2026-10-29)

This patch builds on **Weave-Android-Profile-WYSIWYG-Fix.zip**.

## Daemon: intentionally small, non-wire-format change

File: `native/veilknit-daemon/src/mailbox/runtime.rs`

* `read_inbox_message` and `delete_inbox_message` persist changes to the encrypted local mailbox state, without exporting a *new DHT snapshot* for an operation that did not alter DHT metadata. This can reduce time spent inside the single mailbox actor.
* An inbox `ReadInbox` request whose reply channel has already closed no longer marks a message as read. `ListInbox` requests with closed reply channels are also discarded.
* No changes to message encryption, signatures, DHT COW schema, daemon API JSON, mailbox send acknowledgements, startup recovery, network read/write algorithms, or 150-second send deadline.
* IMPORTANT: this does **not** fix long-running `mailbox_network_commit` calls. Changing the daemon actor/outbox architecture safely requires a separately tested version with durable queue state, cancellation safety, and committed-vs-locally-queued acknowledgements.

## Weave: receipt-driven adaptive custody

Files: `app/src/main/java/app/weave/GroupRuntime.kt`, `GroupCustodySelectionV2.kt`

* Still only samples arbitrary peers for **public** groups. Private/unlisted/member-only group behavior is unchanged; private group identifiers are not disclosed to generic peers.
* Preferred candidates are peers with **verified, unexpired custody receipts for the SAME group**, including peers not currently listed by general discovery. This is evidence of previous custody, not guaranteed present-day availability.
* Initially queues up to 3 custody requests, instead of 6. A bounded delayed check at 35 seconds inspects signed, currently valid receipts for the exact event. If fewer than 2 distinct custodians have acknowledged storage, it queues at most 3 additional candidates, with no duplicate targets.
* Local event creation and the direct/private/witness moderation transports are unchanged. Message queuing is *not* evidence of custody, so the old `CUSTODY_STORE_SENT` line is renamed to `CUSTODY_STORE_QUEUED`; `CUSTODY_RECEIPT` remains the proof to count.
* At most 128 delayed escalations are in memory; these tasks are canceled on session shutdown.  Existing bounded custody worker queues are retained.

## Validation

* 7 executable Kotlin selector checks passed (selection precedence, deterministic order, bounds, duplicates, empty peers).
* Rust compiler/cargo and Android SDK builds unavailable in this environment; full APK build NOT verified.
* Gradle wrapper attempted offline, but Gradle 9.4.1 distribution was not cached, and network access was blocked.
* No device/network delivery simulation performed.

## Device tests before calling this stable

1. Back up a daemon account and record the current working APK/source. Test on a noncritical account first.
2. Post to a public group with 2+ participating phones online. Look for `CUSTODY_SELECTED`, `CUSTODY_STORE_QUEUED`, and **verified** `CUSTODY_RECEIPT` lines; verify the 35-second standby skips when two receipts arrive.
3. Repeat with a moderator offline and 1-2 custodians offline; expect standby escalation, but do not treat queued sends as successful storage.
4. Restart both apps and repeat. Confirm existing inbox messages remain readable and marked-read state persists.
5. Monitor `mailbox_network_commit` latency and backlogs; long commits remain a known limitation until a later daemon release.

## Rollback

Apply only the source diff (no database migration needed), or restore the previous source build. Both the Rust daemon mailbox format and the Kotlin group event/witness format are unchanged.
