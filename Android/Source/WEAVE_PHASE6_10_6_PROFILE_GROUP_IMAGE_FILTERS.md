# Weave Phase 6.10.6 — Profile + Group Image Filter Coverage

## What changed
- Added moderation/filter coverage for **profile page images** rendered inside `PageCanvas`.
- Page rendering now draws loaded profile image elements through `FilteredImage(...)` instead of bypassing the content filter.
- Added a `skipImageIds` path to the canvas renderer so filtered profile images are not drawn twice.
- Confirmed group avatar / group thumbnail rendering remains routed through `FilteredImage(...)`.
- Kept group post preview thumbnails routed through `TinyThumbnail(...)`, which uses `FilteredImage(...)`.

## Why
Previously, profile pages rendered images directly via the canvas drawing path, so opening a profile with an NSFW image would not trigger the image classifier at all.

## Notes
- This patch is source-level only; final Android compilation still needs to be run on the user's machine.
- Content filter models still need to exist at `app/src/main/assets/content_filter/`.
