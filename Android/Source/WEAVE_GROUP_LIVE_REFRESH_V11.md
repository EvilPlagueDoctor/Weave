# Weave Group Live Refresh v11

## Problem fixed

Already-discovered groups could remain stuck on an old post list even after reopening Weave or
leaving and reopening the group. Group branch headers were force-refreshed, but the Group Pulse
(subkey 1, containing the current post/conversation previews) was read with `force_refresh=false`.
That allowed the daemon/local DHT cache to satisfy repeated reads with stale Pulse data.

## Changes

1. `GroupBranchNetwork.readPulse()` now defaults to a forced public DHT refresh.
2. The compatibility `GroupNetwork.readPulse()` path now also defaults to forced refresh and honors
   its `force` parameter all the way to the daemon.
3. `GroupDetailScreen` immediately refreshes the selected branch whenever the group is opened (or
   its selected moderation branch changes).
4. While a group remains on screen, its selected branch is refreshed every 10 seconds.
5. Compose automatically cancels the polling coroutine when the group screen leaves composition,
   so discovered groups are not continuously polled in the background.

This keeps discovery lightweight while making an actively viewed/reopened group fetch the current
Pulse instead of relying on the persisted/local cached copy.
