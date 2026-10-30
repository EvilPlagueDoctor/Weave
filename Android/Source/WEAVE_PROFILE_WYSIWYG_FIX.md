# Weave profile authoring: editor / published text consistency

The advanced editor formerly rendered authored text using Android `TextPaint` +
`StaticLayout`, while published profiles render text with Compose `Text` inside a
`SelectionContainer` so text can be selected and copied. The two paths could
produce visibly different text size, spacing, and wrapping, particularly when
Android's accessibility font scale was not the default.

This patch:

- Uses one `authoredPageTextStyle(Element)` for **both** paths, including the
  editor's inline `BasicTextField` and the selectable profile viewer.
- Replaces the advanced editor's authored-text `StaticLayout` drawing with
  Compose `TextMeasurer` / `drawText`, using the same authored width and style
  that the live viewer uses.
- Applies the same text measuring to buttons, links, and static placeholders
  drawn by the shared canvas renderer.
- Leaves selection gestures in the live viewer unchanged.
- Does not change the stored profile document or require republishing old
  profiles; newly displayed profiles use the same rules in both views.

## Device checks

1. Open an older profile with the advanced editor and compare its body/title
   to **Draft** and **Live** screens. Font sizes and line breaks should be
   much closer, and text should still be selectable in the viewer.
2. Repeat with Android Settings > Display > Font size increased, to verify
   the editor and live viewer scale text consistently.
3. Test left/center/right aligned text; bold, italic, underline; multiline
   paragraphs; and nested text inside old profile blocks.
4. Test inline text editing, element dragging, swiping between profiles,
   selection/copy handles, and image long-press menus for gesture regressions.

The editor deliberately fits a page within a workspace with margins while
live profiles expand to the device width, so an unusually narrow editor or
another device width can still produce small wrapping differences. The
simple editor's page height calculation also uses its own text measurements;
that is a separate area to revisit if long basic layouts overlap.

No Android APK was compiled in the patch-generation environment. Build and
verify on your device before publishing an update.
