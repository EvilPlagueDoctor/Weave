# Weave Messaging Policy dev5 (0.12.0-dev5 / versionCode 49)

Changes from dev4:

- **Seven-day maximum group ServiceRequest witness/intake TTL**, requested by `GroupBranchNetwork.OPEN_INTAKE_TTL_SECONDS` (604800 seconds). VeilKnit's *publisher and recipient validator* apply a service-ID-specific upper bound to four SHA256 IDs: Weave open/private intake and public/private group event witness. Every other ServiceRequest remains subject to the existing 3600-second maximum; widgets still explicitly request 3600 seconds. No DHT schema or signature format changes.
- **Public group custody open to all Weave nodes**, whether joined or not. A signed, structurally valid public-group custody store request can be retained by a participating node. When the receiver doesn't yet know the group, the event is retained in the bounded custody store but not served until the group is confirmed public. For known private/unlisted groups, existing access restrictions remain. Existing replay, abuse, and quota guards remain.
- **Moderator branches default to show new submissions** if group policy allows Everyone; existing explicitly approval-gated policies retain their preapproval default. Branch owners can change **Automatically show new submissions** from group view; this is persisted in the branch header, with backward-compatible decoding for older headers. In automatic mode, owned branches may accept new posts; spectators combine the branch's published Pulse with hash-validated, signed pending public submissions, omitting removed/rejected entries and deduplicating accepted entries. Branch moderator choice is not overwritten by gossip.
- **Normal post details show verified retained public posts/replies** when the selected branch has not yet published them. Existing raw/Curator access remains available separately. Raw comments do not revive entries already marked accepted/rejected/ignored on the selected branch.
- **Less repeated DHT traffic:** a bounded 128-entry in-memory cache reuses previously fetched, append-only group message objects. Payloads are still hash-checked against signed events at use sites.
- **App build version 0.12.0-dev5 / code 49**. No account namespace or local database schema change, and no widget code change.

## Critical limitations / rollout cautions

1. **A seven-day TTL is a maximum, not a seven-day storage guarantee.** The daemon has active ServiceRequest limits (32 per sender, 8 per host by default), page limits, retention/eviction, and must contact peers. A prolific group can exhaust witness slots well before seven days; gossip and normal mailbox transport remain needed. Next scalability step should be an expiring, paginated event-reference inventory rather than unlimited ServiceRequest expansion.
2. **Mixed-version peers:** older VeilKnit versions only accept one-hour ServiceRequests. Older peers may reject seven-day group witnesses until upgraded. The application still offers gossip and the direct/mailbox paths.
3. **Unrecognized public groups:** custody nodes receive authenticated signed requests but do not fetch or expose unknown-group content to unverified clients. Storage is finite; anti-abuse quotas still apply. Private-group data must continue to use encrypted intake, not the open group gossip inventory.
4. **Moderation persistence:** accepted posts continue to be shown from the branch Pulse, and its removal notices and event states suppress pending overlays. Absolute deletion correctness across arbitrarily old posts needs a durable, unbounded/compacted branch-specific removal index; the existing Pulse's removed-post summaries are bounded. Direct UI fallback intentionally excludes entries already marked Accepted to avoid resurrecting accepted-then-removed posts.
5. **Neither Kotlin/Android nor Rust was compiled in this offline environment**. Run the CI build and test with two phones and an offline moderator before deploying broadly. Widget TTL changes must be explicitly regression-tested.

## Suggested two-device smoke test

1. On both phones install the *same dev5 build*. Check version in Settings; do NOT uninstall or clear data. Back up accounts first.
2. Start with an ordinary **public** group where the creator/claimer is offline. Phone A posts; Phone B opens that group with a selected moderator branch and should see a signed pending card in its normal feed, without Curator. Open the card and observe pending replies. Restart B and check reconciliation.
3. On the branch-owner phone, turn off **Automatically show new submissions**, and verify that pending content disappears from the normal view for that selected branch but can still be accessed through Raw/Curator. Turn back on, and verify the setting is propagated to a second device.
4. Remove a post on the branch and ensure that it remains hidden from the normal branch view despite incoming gossip duplicates. Check other branches remain independent.
5. Confirm logs: `WITNESS_ENCODE`, `TRANSPORT_PUBLIC_WITNESS_QUEUED`, `CUSTODY_STORED`, `CUSTODY_RECEIPT_VERIFIED`, `BRANCH_AUTO_SHOW`. The queued milestone is not a remote delivery receipt. Inspect actual `expires_at` values from the daemon for a full seven-day assertion.
6. Verify widgets still use one-hour public requests and function normally. Re-test after airplane-mode/background-resume transitions.
