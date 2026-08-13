# VeilySocial Profile Format (VSPF) v1

## Design rules

1. Pages are declarative data. Nothing in VSPF executes code.
2. Widgets are opaque placeholders in v1 and occupy only a bounded rectangle.
3. Coordinates and sizes are normalized percentages where practical so pages survive different viewport sizes.
4. Built-ins are named local resources.
5. Small decorations may share one future Decoration Pack DHT. References include a local `based_on` built-in fallback.
6. Full images/audio/video use separate media records and are never embedded in the page document.
7. Remote content has geometry/title metadata so the renderer can lay out the final page before content arrives.
8. Viewer safety/accessibility preferences are expected to override author display requests later.


## Editor workspace convention (Hotfix 4)

The editor presents three isolated workspaces without adding another serialized field:

1. **Background:** the root Block's background plus root-level `Stamp` children. Other content is hidden and not hit-testable.
2. **Boxes:** root-level non-Stamp elements (normally `Block` elements). Child contents may be rendered for context but are not selectable in this mode.
3. **Foreground:** the children of one entered root-level Block. The editor may zoom the viewport into that Block, but the stored coordinates remain normalized to their existing parent and are not rewritten merely because the editor zoomed.

This is an editor interpretation of the existing tree and therefore does not change VSPF v1. Older v1 pages with direct non-Stamp root children remain readable; new pages should normally use root Blocks as their box-level containers.

## Local transport envelope

The prototype writes the network-shaped payload into a text file:

```
VEILYSOCIAL_PROFILE_V1\n
BASE64(VSPF_BINARY)
```

## Binary primitives

- integers: little-endian
- `u8`, `u16`, `u32`, `i32`
- `f32`: IEEE-754 little-endian
- string: `u32 byte_length` + UTF-8 bytes
- bool: `u8` 0/1
- colours: `u32` ARGB (`0xAARRGGBB`)

## Header

- magic: 4 bytes `VSPF`
- version: `u16` = 1
- profile id: string
- profile name: string
- default page id: string
- page count: `u16`
- pages...

## Page

- id: string
- name: string
- aspect ratio `f32` (width / height; default 0.60)
- root block

Every page has a root `Block`. The root fills the page viewport and owns the page background.

## Common element rectangle

- element id: string
- editor name: string
- x, y, width, height: `f32` normalized 0..1 relative to parent
- z index: `i32`
- visible: bool

## Background

- kind: `u8` (`0 solid`, `1 linear_gradient`)
- solid ARGB: `u32`
- gradient start x/y/end x/y: `f32`
- stop count: `u8` (2..8 for gradient)
- each stop: position `f32` + ARGB `u32`

Start/end points are normalized to the containing block. This supports horizontal, vertical, diagonal and arbitrary fades without images.

## DecorationRef

- kind: `u8` (`0 builtin`, `1 decoration_pack`)
- builtin name: string
- pack record key: string
- item id: `u32`
- content hash: string
- based-on builtin name: string

For a future remote custom decoration, the renderer immediately uses `based_on_builtin`; when the actual item resolves and validates it replaces the fallback without changing layout.

## Elements

Element tag (`u8`):

1. Block
2. Text
3. Link
4. Button
5. Stamp
6. Media
7. WidgetPlaceholder

### Block

- common rectangle
- layout: `u8` (`0 freeform`, `1 column`, `2 row`)
- background
- border decoration ref
- border thickness: `f32`
- clip children: bool
- scroll children: bool
- sticky mode: `u8` (`0 normal`, `1 sticky_parent`, `2 float_viewport`)
- child count `u16`
- children recursively

### Text

- common rectangle
- UTF-8 text
- font size `f32`
- ARGB colour
- alignment `u8`
- bold bool
- italic bool

### Link

- common rectangle
- label
- target type `u8` (`page/profile/post/community/dht/external`)
- target string

### Button

- common rectangle
- label
- decoration ref
- target type + target string

### Stamp

- common rectangle
- decoration ref
- rotation degrees `f32`
- opacity `f32` 0..1
- flip X/Y bool

### Media

- common rectangle
- media kind `u8` (`image/audio/video`)
- record key string
- content hash string
- intrinsic width/height `u32`
- title string
- description string

The page never contains the media bytes.

### WidgetPlaceholder

- common rectangle
- label string
- future widget record key string (empty in current prototype)
- future widget item id `u32`

## Validation limits in v1

- max pages: 64
- max nesting depth: 16
- max elements per page: 2048
- max text field: 16 KiB UTF-8
- max gradient stops: 8
- max normalized element width/height: 1.0
- decoration item maximum encoded size (future pack): 64 KiB
- decoration item maximum dimensions: 256 x 256
- total decoration pack target cap: 1 MiB
- media is separate and gets its own policy; the page stores only references/metadata

The limits are constants in both implementations so they can be tightened before networking without changing the basic model.
