# Weave Groups v2 — Phase 6 UX + Curation

Version: `0.9.0-groups-v2-phase6-ux-curation` (versionCode 25)

## Consumer UI

- Compact profile header actions no longer wrap on narrow screens. Activity, Advanced, Edit, Publish/Publishing and media-upload progress stay on one line and truncate when space is tight.
- Group-mode bottom navigation now uses the supplied icon artwork without text labels:
  - Snapshot
  - Search
  - Groups
  - Curator
- People mode keeps the same four meanings (Home, Search, People, Me) with the matching supplied icons.
- Group Curator/Me now shows the current account identicon; tapping it opens Settings, matching the profile-side interaction.
- Group cards, search results, management rows and group headers now show a small group thumbnail when one exists.
- Group create/manage can choose, change or remove a group picture. The chosen image is sanitized/re-encoded by the existing media pipeline and only a small embedded thumbnail is published with group metadata.
- People Home adds up to three Suggested people. Ranking is mostly MinHash similarity with a small, stable half-hour jitter so the list has some variety instead of becoming a fixed top-three ranking.

## Moderation branch UI

- The normal group-page `Claim moderation` action has been removed. Manual claiming remains in Settings via pasted group link.
- The existing authority-continuity flow remains: quiet concern after roughly 24 hours and, when appropriate, an independent Claim suggestion after roughly 48 hours of absent authority plus waiting/recent activity.
- Tapping the Original/Claim branch label always opens the branch menu, even when Original is the only known branch.
- The branch menu includes a Curator view. It exposes locally retained Groups-v2 post submissions (pending, accepted, rejected, or custody-retained) without pretending those items are visible on the selected moderation branch.

## Posting feedback

- A post appears locally as soon as submission begins.
- While media/content is being published it is marked `Uploading…`.
- After submission it remains visible to its author as `Not visible yet · waiting for the selected moderation branch` until the selected branch exposes it.
- Once the Pulse contains it, the optimistic card is replaced by the normal visible post card.

## Owner / moderator / claimer controls

For a person who owns the selected moderation branch or has `canModeratePosts` permission, each visible root post has a menu with:

- **Ban user** — future submissions from that author are rejected by this moderation branch. Other branches remain independent.
- **Delete this post** — removes the post from this branch without destroying the author's source object. An optional public reason is stored in the branch Pulse and shown as a removal notice.
- **Modify this post** — publishes a branch-local curated copy and points this branch at the copy. The original author's source object is not changed. Curated copies carry explicit `curated_by` / `curated_from_hash` provenance and the UI labels them as branch-modified.

## GroupStore performance cleanup

- Directory refreshes now update all learned branch pointers in memory and persist the encrypted Groups state once, instead of persisting once per directory entry.
- Verified branch headers plus their advertised known branches can now be committed as one state update.
- Branch refresh/link resolution no longer writes the same fetched Pulse twice in immediate succession.

This specifically removes the pathological case where a 128-entry group directory could trigger roughly 129 complete encrypted Groups-state serializations while holding the GroupStore monitor.
