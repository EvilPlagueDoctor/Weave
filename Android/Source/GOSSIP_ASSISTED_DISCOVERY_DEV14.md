# Weave dev14 — Gossip-assisted profile discovery

Base: dev13 (Veilid 0.5.8; security/account isolation and walker startup recovery retained).

## Behavior

- Incoming social `Summary` gossip now creates discovery leads for unread profile references, previously downloaded profiles missing their VSPF layout, and profiles advertising newer generations. Optional `published_profile` and `advertises_groups` flags let upgraded clients choose valuable leads first. These two fields are **hints, not verification**. Social gossip protocol version remains 1, and older clients ignore the optional fields.
- Direct `ProfileAnnounce` and `SimilarityResponse` gossip use the same bounded queue instead of bypassing the root trust check.
- Once a group hint passes authoritative group-branch DHT verification, its owner and creator become priority leads. Verified group directory results also feed public-profile discovery.
- On each connected worker-loop tick, the queue dispatches at most six leads, sharing at most three in-flight app-root lookups with existing discovery; retry is bounded. Up to 256 leads remain in memory, expire after 30 minutes, and are cleared whenever the authenticated daemon account/session resets. Previously encrypted gossip observations can be requeued by the same account after restart.
- General peer search is still automatic and runs at dev13's 15s warmup / 45s background cadence. Ordinary profile verification tasks now use a shallower pending batch (maximum eight) so newly learned gossip candidates are less likely to wait behind queued refreshes.
- Gossip claims can **never replace a DHT-verified profile hint**, even when they advertise a higher generation. The real profile root is obtained from `get_app_root` (or a previously authenticated profile root), not taken directly from gossip. Published content is still checked against its main-DHT/root binding, length, and blob SHA-256. Suspicious/newer claims can trigger rechecking but never become trusted fields by themselves.
- Group advertisement flags sent in profile summaries are limited to publicly visible groups; they don't deliberately advertise private/member-only groups.

## After building

Test with two disposable accounts and the same signing key. Preserve existing app data and backups. Start A and B; publish A's profile and public group, then leave B on Home/Search without touching Refresh. Confirm B's diagnostics show `gossip discovery: dispatched=...` and that B discovers A's published profile and group. Check counts at 1, 3, and 5 minutes. Test the account switch B→A→B to ensure no cached editor or profile state crosses accounts.

Also test adversarial/stale gossip: a fabricated root or future generation should cause only a bounded app-root/DHT recheck, never overwrite an already verified profile or publish another account's data.

## Build notes

The source ZIP deliberately does not ship `native/veilknit-daemon/Cargo.lock` because the upstream dev13 ZIP omitted it. When applying the Git patch, your locally regenerated Veilid **0.5.8** `Cargo.lock` is preserved untouched; commit that working file with the source update. `versionCode` is 58.

**Validation:** New queue compiled and passed standalone Kotlin smoke tests. Patch applies cleanly to the dev13 archive. Complete Android/embedded Rust build and phone testing were not available here.
