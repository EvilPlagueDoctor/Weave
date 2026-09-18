# Weave Widget Language — source-only sandbox draft v1

This build changes the widget trust model so **published widgets are source-only**.
Executable widget bytecode is never accepted from a publisher.

## Trust boundary

For a received widget, an ordinary Weave client now follows this pipeline:

1. Read the source-only package.
2. Canonicalize UTF-8 source line endings.
3. SHA-256 the source and compare it with the pinned source hash.
4. Compile the source with the viewer's own local widget compiler.
5. Pass the locally-produced VWB1 bytecode through an independent verifier.
6. Derive capabilities from the verified program rather than trusting package metadata.
7. Run only the small set of operations implemented by the local widget runtime.

A modified publishing client cannot add a private opcode, native call, APK/DEX fragment,
HTTP function, filesystem function, camera function, etc. to another person's ordinary
Weave client because the received package contains no executable bytecode and the receiving
client only compiles grammar it knows.

A person can modify *their own* open-source Weave client to weaken its own sandbox. That
cannot change the language/runtime enforced by other people's clients.

## Package formats

### Published

Envelope: `WEAVE_WIDGET_SOURCE_PACKAGE_V1`

Contains:
- widget/provenance metadata
- pinned source SHA-256
- descriptive size/capability metadata (cross-checked against source)
- UTF-8 source

Does **not** contain:
- VWB1 bytecode
- native code
- APK/DEX/JAR content
- arbitrary executable payload

### Local encrypted vault

Envelope: `WEAVE_WIDGET_LOCAL_SOURCE_PACKAGE_V2`

This is also source-only. The compiled bytecode is rebuilt when the local package is loaded.
The local form may additionally retain the source backup used by Link/Fork continuity.

Old Phase-6.1 `VEILYSOCIAL_WIDGET_PACKAGE_TEXT_V1` packages remain readable. Their historical
bytecode must match a deterministic local compile, but that old bytecode is then discarded;
the runtime uses freshly compiled and verified bytecode.

## Independent verifier

`VeilWidgetVerifier` is deliberately separate from the source parser/compiler. It rejects:
- malformed/trailing bytecode
- unknown enum/opcode values
- oversized bytecode
- duplicate or malformed node IDs
- non-finite/out-of-bounds geometry
- invalid font sizes
- excessive nodes/events/actions/timers
- missing event/action targets
- invalid visibility values
- oversized strings
- capability metadata that does not match executable behavior

Current hard limits include 64 visual nodes, 64 events, 8 repeating timers, 32 actions per
event, 64 KiB of source, 256 KiB of local bytecode, and a minimum repeating timer interval of
250 ms. These are prototype limits and can be benchmarked/tuned later.

## v1 language currently implemented

The first draft intentionally remains small. It supports:
- widget declaration + recommended width/height
- box, text, button, and text-input visual nodes
- tap events
- bounded repeating timers
- literal text assignment
- safe host time formatting
- visibility changes
- opt-in Public Widget Networking v1 using declared inputs and inert text

## Phase 6.6 public networking

Networking remains a host-controlled capability rather than a general-purpose network API. Source must contain `online = public`; otherwise input declarations and `network.*` actions are rejected by the compiler/verifier. Current limits are:

- at most 32 declared inputs per widget,
- at most 2 input values in one user action,
- button inputs carry no sender-controlled value,
- one optional inert UTF-8 text line per user action, at most 1 KiB,
- only the five fixed text slots in a participant Session DHT are read as recent messages,
- no timer-driven network sends,
- all received fields are length-delimited, canonical, and validated against the receiver's locally compiled widget definition.

The participant-owned **Widget Session DHT** rotates and old Session DHTs remain retained for seven days before their used slots are overwritten with a machine-readable tombstone carrying the message `No ones watching anymore, Lets Disco party!`. A separate publisher-owned **Widget Data DHT** is tied to the widget instance for long-lived non-executable data/aggregates; reading it does not itself grant networking capability to widget code.

Streaming/media-source syntax and the old Live Stream sample were removed in Phase 6.6.

Example:

```text
widget "Hello Widget":
    default_width = 320
    default_height = 180
    warn_on_resize = true
    background = "#FFFFFBFC"

    text message:
        x = 8%
        y = 12%
        width = 84%
        height = 28%
        text = "Hello from Weave!"
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
        size = 16
        color = "#672832"
        background = "#F8DDE1"
        align = center

    on tap(clock_button):
        message.text = time.format("HH:mm:ss")
```

## Deliberately absent

There is no widget API for:
- arbitrary Internet/HTTP/sockets
- arbitrary Veilid/DHT calls
- filesystem paths
- camera or microphone
- contacts/location/device identifiers
- process creation/native code
- Android Views/classes
- reflection/FFI/eval/dynamic code loading

A future feature should be added as a narrow, named host capability rather than by exposing a
general escape hatch.

## Next language steps

Good candidates for the next pass are explicit `property` values, small local/persistent
`state`, basic expressions/counters, and typed widget-to-widget message ports. Those should be
added without changing the source-only publication rule.
