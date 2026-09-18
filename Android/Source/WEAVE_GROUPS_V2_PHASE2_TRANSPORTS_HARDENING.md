# Weave Groups v2 — Phase 2 transports + hardening

This build continues the intentionally incompatible Groups v2 rewrite. Phase 1's signed event format and encrypted account-local `GroupEventStoreV2` remain the authority for submission identity, validation, deduplication and delivery evidence.

## Phase 2 transport model

Every post/comment is still written to its author's content DHT first. Weave then creates one immutable signed Groups-v2 event and sends that **same event** over three independent paths:

1. **Direct-preferred authenticated message** — fast path to a bounded set of currently useful Original/Claim authorities.
2. **Forced private mailbox copy** — an independent persistent copy to the same authorities, using the event's roughly seven-day expiry.
3. **Public witness ServiceRequest** — a spectator-readable reference copy with the daemon's one-hour public TTL. Public/unlisted groups use plaintext signed events; member-only groups wrap the event with the group's 256-bit intake key.

The post body/media are not duplicated into these packets. Receivers fetch the author-owned DHT object and verify the event's content hash.

All three paths terminate at `GroupEventStoreV2.ingest()`. The first valid copy creates the event; later matching copies only add delivery evidence. Network transport never directly creates a second moderation item.

### Bounded fan-out

The event itself remains group-scoped, so it does not enumerate every Claim. This build sends direct/mailbox copies to at most 24 distinct authority owners, preferring the selected branch, Original, then more recently updated branches. Public witness publication uses at most three authority hosts because spectator-readable ServiceRequests do not need one publication per Claim.

These are development constants and can be tuned later as group scale data becomes available.

## Hardening included

### Conflict evidence classification

A same-event-ID/different-body observation is no longer automatically treated as author equivocation.

- `InvalidOrUnverified`: the conflicting body does not have a valid independent signature.
- `DifferentSigner`: it validates, but not as the same signing identity.
- `AuthenticatedEquivocation`: both canonical bodies validate under the same author/signing key.
- `LegacyUnclassified`: compatibility label for conflict evidence persisted by the Phase 1 build.

Only `AuthenticatedEquivocation` is suitable for future author-reputation consequences. A malformed relay copy can instead become evidence about the relay once custody/reputation is implemented.

### Idempotent branch decisions

Branch processing is protected by `(event_id, branch_id)`:

- an in-flight guard prevents simultaneous transports from racing into two writes;
- existing Groups-v2 Accepted/Rejected state suppresses later duplicates;
- the current branch index is checked before accepting, which also protects legacy duplicates;
- moderation tasks use the canonical Groups-v2 event ID when available.

This addresses the Phase 1 test where one old submission was auto-accepted twice and advanced Pulse generation twice.

### Progressive reconnect backoff

Repeated connection failures now back off approximately:

`1.5s -> 3s -> 6s -> 15s -> 30s`

A successful full initialization resets the sequence. A daemon process/account change gets a short reconnect path. This reduces Binder/log/battery churn when the daemon is stopped, missing, or awaiting reauthorization.

### Gossip-summary log suppression

Repeated identical generic gossip summaries are still processed, but their log lines are deduplicated per source/digest and normally emitted no more than once per minute unless the summary changes.

### Reputation-block diagnostics

Transport failures containing a daemon reputation/block rejection now emit a focused `REPUTATION_BLOCK_HINT` line with event, target and transport. This does not alter reputation policy; it only makes a future recurrence easy to identify.

## Transition behavior

New Groups-v2 **post and comment submissions no longer use the legacy ServiceRequest submission envelope in parallel**. They use the canonical Phase 2 transports above and are bridged into the current branch index/Pulse/moderation machinery only after event validation and DHT content-hash verification.

Legacy group-control operations (claims, reports, joins, moderator actions, reconciliation, etc.) remain on the existing protocol for now. They can migrate independently later.

## Receiver security rules

For Phase 2 direct/mailbox/public-witness author delivery, the daemon-authenticated sender/requester must match the event's claimed `author_main_dht`. The signed event is then independently verified. Remote signing keys are still `RemoteUnbound` until a later profile-key binding phase.

The routing-only target owner in the wrapper is not part of the signed event. It can route a direct/mailbox copy but cannot change the event's group, payload, content hash, author, expiry or event ID.

## Current non-goals

This build does not yet implement Group Custodian replication/receipts, Claim recovery from custodians, automatic unattended-group Claim prompts, profile binding of remote app-signing keys, or reputation score changes. Those remain later phases.
