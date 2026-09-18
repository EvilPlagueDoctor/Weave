# Weave group layout / comments v4

Integrated changes:

- People and Groups use the same navigation idea:
  - Home is your followed/joined space.
  - Search is discovery.
  - People Search shows recently discovered users when the query is blank.
  - Group Search shows known/discovered groups and accepts `weave://group?...` links.
- Group pages no longer expose "Claim moderation".
  - Public group pages have a chain-link button that copies a creator-root group link.
  - Settings has "Claim moderation of group" with a link paste field.
- Compact group header:
  - title;
  - `members · visibility · Original/Claim`;
  - tapping Original/Claim opens known moderation branches;
  - copy-link plus Join/Leave on the right.
- Description is one line by default with ellipsis; tap to expand/collapse.
- Featured area sits immediately below the description.
  - branch owners and moderators with permission see Edit;
  - can use a custom message or select a recent group comment.
- Every group has a built-in Discussion conversation.
  - comments are `WeaveMessage` objects stored in the author's DHT;
  - branch moderation stores/removes only the current ObjectRef;
  - the permanent event journal retains the content hash/action but no removed retrieval link.
- Group Home still uses the compact Pulse.
  - opening a group hydrates up to 100 visible Discussion entries from the selected branch's
    moderation index, so the full group view is not limited to the 3-message Pulse preview.
- Branch-local pinning:
  - Pin/Unpin appears beside comments for authorized moderators;
  - pinned IDs are stored in the branch header;
  - pinned comments sort to the top.
- Moderator grants are granular:
  - moderate posts;
  - approve members;
  - handle reports;
  - pin comments;
  - edit the featured area.
- A claim remains an alternate moderation branch of the same group. A group link always targets
  the Original creator root so pasting a link into Search or Settings resolves the canonical group.

Private/member-only group key distribution is still a separate unfinished subsystem, as before.
