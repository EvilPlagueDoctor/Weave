# Weave Groups: Post Images v6

This revision adds optional picture attachments to group posts using the existing Weave message
shape:

- `thumbnailBase64` stores a small embedded preview for the post list / pulse.
- `fullMedia` stores the full-size remote blob reference.

Behavior:

- The **Create a post** dialog now supports adding one optional picture.
- The selected image is sanitized through `LocalMediaStore`, just like other Weave media.
- On post creation:
  - the full-size image is uploaded as its own blob;
  - a small thumbnail is generated and embedded into the post message;
  - the post root message carries a `fullMedia` image ref for later lazy loading.
- Group post previews now show the embedded thumbnail when present.
- Opening a post does **not** automatically fetch the full image.
- The post screen shows the thumbnail and a **tap-to-load** interaction; only then does Weave
  download and cache the full-size image.

Notes:

- This first pass adds picture-picking to top-level posts.
- The wire format already supports media in any `WeaveMessage`, so the same pattern can later be
  extended to comments without changing the protocol again.
