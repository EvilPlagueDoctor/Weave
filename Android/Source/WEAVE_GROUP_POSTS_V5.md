# Weave Groups: Posts -> Comments v5

This revision changes the group discussion model from one flat group comment stream to:

Group
  -> Posts
      -> Comments

Key behavior:

- The group page now shows a list of top-level posts and a `Create a post` button.
- A top-level post has a title and body. It is a normal `WeaveMessage` with an optional `title`.
- Each top-level post creates a conversation ID of the form `post:<root-message-id>`.
- Opening a post navigates to a dedicated post screen.
- The post screen fetches the full root post and the full visible comment set for the selected
  moderation branch only when opened.
- Comments are ordinary `WeaveMessage` objects in the post's conversation and carry a root/ref
  back to the top-level post.
- Group Pulse remains compact:
  - one root post preview per post;
  - total comment count;
  - only a few recent comment previews;
  - no need to pull every post/comment DHT when opening the group.
- Pinning remains branch-specific and applies to comments inside a post.
- Featured content can still be a whole post or a specific comment.
- Removing the root post removes that whole post from the selected branch's Pulse.
- Removing a comment removes only that comment and decrements the post's comment count.
- Full opened-post bodies are held in a transient, branch-specific cache so the compact Pulse's
  truncation never truncates the actual post/comment screen.

The underlying creator/claimer moderation model, group links, Search discovery, join/leave,
featured area, and granular moderator permissions are unchanged.
