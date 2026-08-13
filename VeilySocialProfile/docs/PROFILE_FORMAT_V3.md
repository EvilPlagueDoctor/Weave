# VeilySocial Profile Format (VSPF) v2

VSPF v2 is the local/network-shaped page document format used by the profile designer.
The `.txt` prototype envelope is only a transport stand-in for future DHT storage: the payload is binary VSPF data encoded as Base64.

Text envelope written by this build:

```
VEILYSOCIAL_PROFILE_V2
<Base64 of binary VSPF payload>
```

The decoder remains backward-compatible with `VEILYSOCIAL_PROFILE_V1` / binary version 1 files.
When a v1 profile is loaded, missing v2 properties receive safe built-in defaults and the next save writes v2.

## Why v2 exists

The page editor now supports persistent typography and button appearance rather than treating those as editor-only presentation choices.

### Text elements now store

- UTF-8 text
- stable built-in font ID (`Default1` in this prototype)
- font size
- ARGB text colour
- alignment
- bold
- italic
- underline

The stable font ID is intentionally separate from the localized font name shown by the editor. Future versions can extend font references to custom font packs without changing user-visible text into an identifier.

### Button elements now store

- button label
- border/decorative reference
- solid or multi-stop linear-fade background
- stable font ID
- font size
- ARGB text colour
- alignment
- bold
- italic
- underline
- typed navigation target

Button images are represented in the UI as a future feature and are not serialized yet.

## Localization rule

Human-facing editor labels come from platform resource tables. Serialized identifiers such as `Default1`, `Thin1`, `Star1`, element-type tags, and RecordKeys are language-neutral stable identifiers and must not be translated.
User-authored text remains exactly as authored.

## Compatibility

- v2 Android can read v1 and v2.
- v2 C++ codec can read v1 and v2.
- v2 writers emit v2.
- The page/tree structure, normalized geometry, media references, decoration references, gradients, and widget placeholders are otherwise unchanged from v1.

## VSPF v3 widget-reference extension

VSPF v3 extends the existing `Widget` element payload after `widgetItemId` with:

```text
widgetSourceHash    string    expected SHA-256 source hash
widgetDefaultWidth  u32       recommended logical width
widgetDefaultHeight u32       recommended logical height
widgetWarnOnResize  bool      author warning that page-side resizing may break layout
```

Readers continue to accept v1 and v2. For older widget references the defaults are an empty source hash, 320 x 180 recommended size, and resize warning enabled.

The source hash pins the profile to the widget code version it was authored/tested against. A network/local widget object whose current source hash differs is not silently trusted as a replacement.
