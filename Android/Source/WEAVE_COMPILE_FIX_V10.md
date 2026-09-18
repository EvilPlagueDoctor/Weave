# Weave compile fix v10

Fixes exposed after raising Gradle JVM memory limits:

- Added `Audio` to `WeaveObjectType` in `MessageEnvelope.kt`.
  - Audio upload/playback/link code already referenced this enum member.
  - `fromWire("audio")` now resolves correctly instead of falling back to `Message`.
- Removed the duplicate `LocalContext.current` declaration from `GroupDetailScreen`.
- Added the missing `LocalContext.current` declaration to `GroupPostScreen`, which is used for copying post links to the clipboard.

No content-filter behavior was changed by this patch.
