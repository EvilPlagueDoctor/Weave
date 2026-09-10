# Weave Groups / creator-claimer integration v2

This project directly integrates the group work into the attached current Weave source. It is not an overlay.

Implemented:
- People/Groups mode switch replacing the permanent Activity tab.
- Profile moderation remains reachable from Me; Me shows actionable moderation count.
- Group Home Pulse, group search, group creation/management, featured area.
- User-first discovery via profile-store subkey 2.
- Original creator branch plus permissionless public/unlisted claim branches.
- Branch-specific secondary moderators.
- Pending/Visible/Removed post state.
- Durable branch journals retain hashes/actions, not content refs; Removed strips the branch index ref.
- Public/unlisted pending activity uses spectator-readable ServiceRequests.
- Member-only intake uses spectator-readable ServiceRequests with opaque AES-GCM payloads when a group key is installed; otherwise private authenticated mailbox delivery is used.
- Reports/join requests/moderator actions use authenticated direct + mailbox delivery.
- Member-only groups are non-claimable and are not profile-directory advertised.
- Event IDs are deduplicated across direct, mailbox, public-intake and relayed copies.

Private-group key invitation/distribution remains the next protocol piece.

Direct-integration additions:
- creator/claimer journal reconciliation when a known branch is refreshed;
- branch pointer owner/kind verification before accepting a branch;
- Join / Ask to join / Leave controls on group detail;
- public join approvals/rejections are returned over authenticated messaging;
- quarantined post approval/removal now changes the branch index instead of only dismissing UI;
- creator/claim branch owners can add a secondary moderator from the group screen;
- creator metadata editing is not exposed to claimers, preventing accidental Original-branch publication.
