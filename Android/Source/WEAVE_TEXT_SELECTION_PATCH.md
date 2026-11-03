# Selectable text (2026-10-29)

This patch builds on `Weave-Android-Profile-Swipe-Fix` and retains the ONNX
initialization queue and release-build script from the previous fixes.

- Profile pages: read-only, selectable Compose text elements over the existing
  custom page canvas. Includes nested text in older profiles. The editor still
  draws its previews using the original Canvas renderer.
- Group posts, previews, comments, featured content, group names/descriptions,
  pending post drafts, and profile comment threads: selectable text.
- Home/Search profile names and descriptions, Activity comment text, and a few
  diagnostic text blocks: selectable.
- Links within displayed posts/comments remain tappable via Compose's
  `LinkAnnotation.Clickable`, avoiding a gesture detector competing with text
  selection. Copy/paste uses the native Android selection toolbar.
- Filtering remains outside selection: hidden/unscanned text does not expose
  a selectable copy of the unfiltered content.
- Not all UI chrome is selectable: navigation buttons, labels, menus, and
  editable input fields retain their normal interaction behavior.

## Device test checklist

1. Open a profile with an authored heading and paragraph. Long-press text, move
   selection handles and copy into a message. Check text doesn't overlap images.
2. Swipe to the next profile from a non-text area; long-press a paragraph and
   drag handles without triggering a profile switch.
3. Tap a `weave://` link in a comment to confirm it still opens. Long-press
   ordinary surrounding text and copy; test a URL too.
4. Select text in a group post, comment, and Search result. Tap the post card
   background to confirm opening it still works.
5. Hide a comment with the harassment filter and check it cannot be selected
   until explicitly revealed.
6. Long-press a profile image and verify the Save/Copy image actions still work.
7. Change system font size and inspect profiles with densely packed text.

`PageSelectableTextTest` covers text placement and eligibility. Runtime gesture,
visual layout, and Android builds still require testing on a device.
