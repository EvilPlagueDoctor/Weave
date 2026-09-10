# Suggested first test pass

1. Apply `Apply-Weave-Groups-v2.ps1`, then build normally.
2. Confirm People/Groups mode and Me moderation badge still work.
3. Create a Public group on A; confirm an Original branch DHT and profile-directory entry.
4. Discover A from B; confirm A's created group appears on A's profile.
5. Claim it on B; no creator approval should be requested and the same `groupId` must remain.
6. Switch between Original and Claim; the selection should survive restart.
7. Submit a post while authorities are offline; confirm spectator-readable ServiceRequest activity
   appears as unreviewed and duplicate copies collapse by `eventId`.
8. Allow a post: current branch index must contain hash + `messageRef`.
9. Remove it: same hash/state remains, but current index must contain **no `messageRef`**.
10. Let Original keep a post and Claim remove it; both decisions should coexist.
11. Create MembersOnly; Claim must be rejected, no profile-directory advertisement should be made,
    and normal submissions must use private message/mailbox transport.
