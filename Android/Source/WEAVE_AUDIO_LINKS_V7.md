# Weave Audio + Automatic Links v7

## Audio

Audio can now be added to:
- profile pages from the simple editor;
- top-level group posts.

Privacy/sanitization:
- Weave never publishes the selected source audio container directly.
- Android decodes the source audio to PCM and Weave re-encodes it as a fresh AAC/M4A file.
- Source ID3/Vorbis/MP4 tags, filename metadata, embedded artwork and other source-container
  metadata are therefore not copied into the published file.
- Audio is capped at 10 minutes and the sanitized file is capped at 16 MiB.

Profile behavior:
- `MediaKind.Audio` is used by the existing profile format; no profile format bump is needed.
- Publishing uploads audio before the profile document, just like images.
- Audio players are shown below the current page.
- Remote audio downloads only when Play is pressed.

Group-post behavior:
- A post can contain text, one optional image and one optional audio attachment.
- Images retain the thumbnail + lazy full-image loading behavior.
- Audio is stored as a full-media reference and downloads only when Play is pressed.
- Compact post/Pulse previews show an audio indicator but do not fetch the audio.

## Automatic links

Weave detects links in:
- group post bodies;
- group comments;
- profile comments.

Recognized internal links:
- `weave://group?...`
- `weave://post?...`
- `weave://media?...` for images/audio
- `weave://profile?...`

Sharing:
- group pages already provide a group link;
- post pages now provide a post link;
- image links are copied as `weave://media` rather than raw DHT keys;
- audio players provide a chain/link button when the audio has a network location.

External links:
- `http://`, `https://`, `ftp://` and `mailto:` are detected;
- tapping one never opens another app immediately;
- Weave first shows a confirmation warning with the destination.

Raw `dht://` references are recognized but are not treated as a trusted typed media link; Weave asks
the user to use a typed Weave link when possible.
