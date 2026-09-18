# Weave Phase 6.5 — Advanced Editor Polish

This pass focuses on the Advanced profile editor interaction model.

## Inspector layout

- Removed the duplicate `This Box` / `Background` mode-status card from below the Inspector header. The active mode is already shown in the editor header.
- Moved `Pages & layers` into that upper Inspector position.
- The Pages & layers list has a capped, independently scrollable expanded area so large profiles do not push the remaining controls off-screen.
- Added a third quick-action button for Undo beside Delete and Clone. It uses the editor's existing undo history.

## Delete / Clone feedback

Delete and Clone now show the same Android toast-style feedback area used by the existing `A profile must keep at least one page` message.

Examples:

- `Deleted page`
- `Deleted Text`
- `Made a clone of the box`
- `Made a clone of the Widget`

The message is shown only after the requested action actually succeeds.

## Canvas pan + zoom

- Existing two-finger pinch zoom remains.
- A one-finger drag beginning on empty canvas/page space now pans the zoomed viewport.
- A drag beginning on an actual editable item continues to move that item instead, so panning and object movement have separate hit-test behavior.
- Panning is clamped to the zoomed page bounds.
- Tapping the zoom percentage resets both zoom and pan.

## Background mode

When the page root is selected in Background mode, `Move / Size` is now labelled `Page size`, because that section controls the page aspect ratio rather than moving an object.

Decorative background stamps still use `Move / Size` when a stamp itself is selected.

## Bottom edit strip

The standard edit modes remain:

1. Background
2. Boxes
3. Contents

When editing inside a box and a child item is selected, a fourth contextual tile appears immediately after Contents. It identifies the selected item, for example:

- Text
- Link
- Button
- Box
- Stamp
- Media
- Widget

While such an item is selected, the add-tools area is hidden because the object's editing controls live in the Inspector. Selecting the box itself/empty space brings the add-tools area back.

## Editing the current box appearance

While in `Contents` / `Editing inside Box`, selecting the box itself now exposes the Inspector's `Appearance` section so its background colour/gradient/border can be changed without leaving the box.

`Move / Size` remains unavailable for that focused box while zoomed inside it, matching the existing behavior and avoiding confusing movement of an object that is currently being used as the viewport.

## Version

- versionCode: 28
- versionName: `0.9.3-advanced-editor-polish`

## Validation performed in this environment

- Android resource XML parsed successfully.
- `ProfileEditorApp.kt` passed a Kotlin parser-level syntax check (no `expecting` / `unexpected tokens` errors).
- Full Gradle Android compilation could not be run because Gradle 9.4.1 is not cached in this environment and the sandbox cannot reach `services.gradle.org`.
