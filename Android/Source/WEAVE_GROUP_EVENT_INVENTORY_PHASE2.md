# Weave Groups — signed event gossip and session recovery (2026-10-30)

Baseline: `Weave-Android-Messaging-Phase1-Diagnostics.zip`. This build preserves previous ONNX, profile swipes, selectable text, WYSIWYG, and Phase 1 group custody diagnostics.

## Scope and safety

* The embedded **VeilKnit Rust daemon is unchanged**. No change to encrypted account storage, daemon app registration/credential schema, DHT record schema, or mailbox transport semantics.
* Weave automatically detects `authentication_failed` responses for lost/expired **app session tokens**. A single controller reconnect reauthenticates the current embedded account; it does not delete credentials for a missing session. Subsequent RPC calls fail locally until the reconnect instead of flooding the daemon.
* Group posts retain the existing optimistic card and pending/approval state.
* Group comments now appear immediately as optimistic cards in the post view, with **Sending**, **Submitted / awaiting moderation**, and **Could not confirm** states. There is a manual retry; a timeout can leave outcome uncertain and retry may create a duplicate. These optimistic cards are UI state, not themselves durable queue records.
* A new backward-compatible `groups_v2_event_inventory` gossip kind carries up to **three signed event envelopes** (DHT payload references, content hashes, signatures), with an **8 KiB** packet cap. New local events are announced without blocking the composer. A 90-second rotating anti-entropy pass relays references retained for up to seven days. No message bodies or media are embedded in gossip.
* To preserve downgrade readability of the encrypted event store, gossip evidence is persisted under the existing generic `LegacyBridge` evidence type (with the detailed `GOSSIP_EVENT_*` diagnostics separately). No new persistent enum value or schema migration is introduced.
* Only **public groups already known locally** are eligible for gossip event ingestion and retransmission. No unlisted/private group references are broadcast by this feature. Gossip events are not moderation decisions and are **never auto-accepted into owned branches**. A gossip sender is not authenticated as the event's claimed author.
* A receiver verifies the event's Ed25519 signature via the existing app/daemon verification API, then fetches and hash-checks the DHT payload asynchronously. Signature-valid-but-author-unbound events remain labeled accordingly in the opt-in Raw/Curator view. The public post screen also exposes opt-in raw/unapproved replies.
* A delayed custody store request isn't rejected merely for exceeding 15 minutes in the mailbox. The signed event must still be unexpired, the signed request timestamp must be within the event validity bounds, and existing request signature, group authorization, deduplication, and abuse limits remain.
* Identical group branch-hint gossip is throttled to roughly every 30 seconds instead of resending to six peers every eight seconds. Changed hints still broadcast immediately on the next scheduled pass.

## Important limitations

* This is **best-effort** public event discovery, not guaranteed delivery to *every* user. A node that has not learned the group will not retain its event gossip, and a pointer alone cannot keep a payload available. Verified remote custody still needs to work for dependable seven-day retention.
* Other devices must run a version that recognizes the new inventory gossip kind. Older clients continue to process the old hint format and ignore this new kind.
* Existing profile-comment notifications are not yet added to gossip. Owner-approved profile-comment policies remain unchanged; a separate owner-as-viewer reconciliation step is still needed.
* The daemon's slow `mailbox_network_commit` remains an outstanding issue. `mailbox_queued` is not proof of recipient reception.
* Group raw comment fetching is on-demand via a Refresh control, avoiding continuous DHT polling. A fetched payload is re-hash-checked before display.

## Two-phone test

1. Keep a backup of the daemon account; test with a public group known to both phones. This version needs no Rust data migration.
2. Phone A: send a group post; it should appear immediately in the group as Uploading, then Submitted/Awaiting moderation when saved. Post a comment; it should appear in the thread immediately with Sending and later Submitted.
3. Phone B: open the public group's `Raw / Curator` option. New unapproved posts should appear if gossip has delivered their signed pointers and their payload can be fetched. Open an approved root post and choose `Show raw / unapproved replies` for pending comments; use Refresh to check newly received events.
4. On A, look for `GOSSIP_EVENT_ANNOUNCED`. On B, look for `GOSSIP_EVENT_RECEIVED`, `GOSSIP_EVENT_SIGNATURE_VERIFIED`, then `GOSSIP_EVENT_CONTENT_VERIFIED`. Only the last confirms usable payload data.
5. Force-stop/restore one embedded daemon instance while Weave remains up. Look for one `DAEMON_SESSION_LOST` and a `NETWORK_RECONNECT reason=app_session_invalid`, followed by authentication; the earlier flood of `app session was not found` should stop.
6. Delay a custody request longer than 15 minutes (while the signed event still has time remaining) to check it no longer fails with `reason=stale`. A successful `CUSTODY_STORED` and signed `CUSTODY_RECEIPT_VERIFIED` are stronger than queued/send success.
7. Collect Weave diagnostic report and embedded daemon log from both phones. Watch mobile-data use because gossip transport does have real overhead.

## Validation performed

* The new Kotlin packet parser compiled against JVM test stubs, showing no syntax errors.
* Kotlin syntax-level diagnostic scan across changed app files produced no `expecting` / unexpected-token diagnostics; its unresolved Android/Compose symbols prevent full compilation.
* Full Android Gradle/Rust build and real device tests remain **unverified** in this execution environment.

## Rollback

Apply the source patch to the Phase 1 diagnostics baseline only, or restore the baseline ZIP. No persistence migration, no daemon schema changes. No new persistent enum value was introduced; generic event evidence remains readable by the Phase 1 version. Back up the account before changing builds regardless.
