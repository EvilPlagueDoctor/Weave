# Weave group protocol v2

This package implements the agreed creator/claimer moderation model.

## Identity and discovery

A group has one permanent `groupId`. The creator's Original branch DHT is the canonical
`creatorRoot`. A claim keeps the same `groupId` and points back to `creatorRoot`; a fork creates a
new `groupId`.

Discovery is initially user-first. Profile app-root subkey **2** contains a compact directory of
public/unlisted groups the profile created or claims. Member-only/encrypted groups are never
advertised there.

## Moderation branches

The creator and every claimer own separate branch DHTs. Secondary moderators are delegates of one
branch only; they do not mirror it and do not become claimers.

The creator's Original branch is the default. A user's explicit branch choice is remembered.

## Post state and link removal

Current branch state is explicit: `Pending`, `Visible`, or `Removed`.

The durable event journal stores IDs, hashes and actions but **never durable content refs**. The
current moderation index stores a `WeaveObjectRef` only while the post is `Visible`. Pending and
Removed entries are required to have no ref. This preserves the hash/audit trail while actually
removing the branch's advertised retrieval link.

## Delivery

Actual message/image/widget content remains in its author's storage. Authorities exchange small
pointer/event envelopes.

Public/unlisted post and conversation submissions use the daemon ServiceRequest path toward a
small set of known creator/claimer authorities with delegation and spectators enabled and a
15-minute default TTL. These are explicitly unreviewed temporary items.

Reports, join requests, moderator actions, claimer reconciliation and member-only/private group
events use authenticated direct messaging with encrypted mailbox fallback. Reports and join
requests are never spectator-readable.

A branch's current state always outranks a temporary ServiceRequest.

## Claimer synchronization

Creator/claimers keep track of known branch pointers and relay newly observed submissions. Shared
`eventId` values make direct, mailbox, ServiceRequest and claimer-relayed copies idempotent.

Branches share knowledge that an event/post hash existed, but moderation remains branch-local.

## Private/member-only groups

Member-only groups are non-claimable, are not profile-advertised, and never use spectator-readable
intake. This patch does not invent a public representation for them. Full group-key encryption of
their DHT content/member key distribution is still a separate subsystem; until that exists, their
public group root is not published.
