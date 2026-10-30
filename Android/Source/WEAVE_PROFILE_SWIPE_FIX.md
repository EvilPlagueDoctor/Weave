# Weave: profile swipe/navigation fix

## What changed

- Search now opens a profile with a snapshot of the *search results currently displayed*.
  A blank query snapshots the "Recently found" list; a nonblank query snapshots
  `searchResults`. The previous viewer incorrectly used only `ui.recent`.
- Home/Following opens a snapshot of displayed followed and suggested profiles in
  visible order (deduplicated).
- The snapshot travels with `Destination.Profile` in the navigation stack; it
  survives routine UI refreshes, tab switches, and saved navigation restoration.
  Older `profile\0<key>` navigation snapshots are still decoded.
- Previous/next wrap between the last and first profile, but a one-item deck
  does not navigate away on swipe.
- A compact profile counter and previous/next buttons appear for both loaded
  profiles and pending/failed fetches. Pending/failed fetch screens still accept
  horizontal swipes. A retry button allows another fetch after timeout.
- `AnimatedContent` slides left/right when swiping between profiles and fades
  for initial opens.
- The controller preloads only the immediate neighboring profile documents with
  an in-flight guard to avoid redundant verification/download requests.
- Added JVM unit tests for queue ordering, wrap-around, one-item and missing-key cases.

## Manual test on device

1. Search for a term with many users. Open any result. Confirm the `Profile X / N`
   counter matches the visible list's size and order, not merely recent profiles.
2. Swipe right/left; check a visible slide transition and arrows update the counter.
3. From the final profile swipe forward; it should load profile 1. Swipe backward
   from profile 1 and it should load the last profile.
4. On a slow first-time profile, confirm the spinner and counter are visible, and
   both arrow buttons / swipes continue working while fetching.
5. Switch to another tab and back; the original search deck should still be there.
6. Repeat from Following, suggested results, and a single external profile link.
7. Reconnect while viewing a profile; confirm loading or error is visible rather
   than a silently ignored swipe.
8. For a failed fetch, confirm retry is possible. No timeout is interpreted as
   an end-of-list condition.

## Notes

The list is a snapshot of what the user opened. Profiles newly discovered while
viewing a profile are not appended until the user returns to Search and opens
one of the updated results. Prefetch is opportunistic and does not guarantee
all profiles are available immediately on slow Veilid connections.
