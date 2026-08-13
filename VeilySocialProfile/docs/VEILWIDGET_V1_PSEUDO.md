# VeilWidget v1 pseudo-runtime

This build is intentionally local-only. It is meant to test the widget language, compiler, bytecode/runtime boundary, authoring UI, provenance rules, and page-editor integration before any DHT/network behavior is added.

## Security boundary

A VeilySocial profile/page is still inert VSPF data. Only a Widget element may reference executable VeilWidget bytecode.

VeilWidget v1 deliberately has no API for:

- HTTP, DNS, TCP, UDP or raw sockets
- arbitrary Veilid messages or arbitrary DHT reads/writes
- filesystem/process/shell access
- clipboard/device identifiers/location
- viewer camera or microphone

Unknown statements are rejected by the compiler rather than silently ignored.

A `stream` node is different from network access. It is a host-controlled display surface. The widget names a host stream such as `host:gaming`; VeilySocial supplies playback outside the VM. A stream is always paused when first loaded. The language has no autoplay/play instruction.

`TODO(network)`: replace the local host-stream mock with the eventual VeilKnit/Veilid streaming layer. The widget must still only receive the narrow host-stream capability.

## Recommended size

A widget declares:

```text
default_width = 320
default_height = 180
warn_on_resize = true
```

This is a recommendation, not a runtime resize ability. Widget code cannot modify its page rectangle. When inserting a widget, the page editor calculates the available logical size of the selected box. If the recommended widget size is larger than that box, insertion fails with a clear error. The page author may resize an already placed widget; if `warn_on_resize` is true, the inspector warns that the layout may break.

## Source / visual editor

Widget Studio has Visual, Code and Preview views.

- Visual edits regenerate VeilWidget source and compile it into preview bytecode.
- Code edits do not affect the compiled preview until Compile is pressed.
- Preview executes a decoded VWB1 program rather than interpreting the source text.
- Compile + Save writes a local `.widget.txt` package.

The package is a Base64 text envelope around a binary serialized payload. It contains source, VWB1 bytecode, manifest, SHA-256 source hash and provenance metadata.

## Example

```python
widget "Hello Widget":
    default_width = 320
    default_height = 180
    warn_on_resize = true
    background = "#FFFBFC"

    text message:
        x = 8%
        y = 12%
        width = 84%
        height = 28%
        text = "Hello!"
        size = 24
        color = "#7D3440"
        bold = true
        align = center

    button clock_button:
        x = 22%
        y = 58%
        width = 56%
        height = 22%
        text = "Show time"
        background = "#F8DDE1"
        color = "#672832"

    on tap(clock_button):
        message.text = time.format("HH:mm:ss")
```

Current visual node types:

- `box`
- `text`
- `button`
- `stream`

Current event/action syntax:

```python
on tap(button_id):
    message.text = "Hello"
    other.visible = false

every 1 second:
    clock.text = time.format("HH:mm:ss")
```

Timers are bounded and have a 250 ms minimum. The bytecode/runtime also impose node/event/action limits.

## Linking and forking

Local packages simulate the future network provenance behavior.

A Link or Fork records:

- origin widget id
- SHA-256 hash of the accepted origin source
- a local backup copy of that exact source

The UI explicitly asks to save the source backup before creating the Link/Fork.

If the origin package later changes, the linked/forked package reports that the origin hash differs. New code is never activated automatically. The user may:

1. accept the changed origin, preserving the previous source as a backup; or
2. keep the old backed-up source and detach it into an independent local widget.

A VSPF Widget reference also stores the expected source hash. Therefore accepting an upstream update in Widget Studio does not silently change a profile that was pinned to the older code. The profile inspector must explicitly accept the current local widget version.

`TODO(network)`: origin widget ids become widget DHT/item references, source download becomes a DHT fetch, and publishing the detached backup creates the user's own widget record.

## Local storage

For this prototype:

- profiles: app-local `profiles/*.txt`
- widget packages: app-local `widgets/*.widget.txt`
- host streams: simulated in the preview only

No network operations are performed.

## Deliberately deferred from this first pseudo-runtime

The compiler/runtime boundary is in place first. The following pieces from the larger design are intentionally TODO rather than being faked with unsafe shortcuts:

- `state`, device-local state, and public/DHT-backed typed storage
- polls/guestbooks that need public persistence
- bounded `for` loops and user-defined functions
- sprite/procedural animation helpers
- navigation-request events
- imported widget image/font assets
- actual host-stream routing and media decoding

When storage is added, the VM should receive schema-scoped storage operations only; it should still never receive a raw DHT API. The local prototype can back those schemas with text/binary files before the DHT adapter exists.
