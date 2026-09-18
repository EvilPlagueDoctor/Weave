# Weave Phase 6.1 UX polish

Changes in this pass:

- Bottom navigation now gives the selected Home/Snapshot, Search, or Me/Curator section a distinct background.
- Removed Activity and the separate Advanced button from the profile Me header.
- Profile Activity is now opened from Settings.
- The single Edit action opens whichever profile editor (Basic or Advanced) that account used most recently.
- Basic can switch to Advanced, and Advanced now has a Basic control to switch back. The preference is kept in the account-private vault.
- Group post upload/submission status is hoisted above the group detail screen, so navigating around Weave no longer makes an in-flight/pending post appear to vanish.
- A post upload continues while navigating between sections. Returning to the group restores the local pending card and status. Failed submissions retain the text/media draft in memory for retry while the app stays running.
- Successful post submission no longer forces navigation to the post if the user has already left that group screen.

Note: the outgoing post operation is still an in-process job. Android process death/app termination can stop an in-flight upload; durable restartable upload queues are a separate feature.
