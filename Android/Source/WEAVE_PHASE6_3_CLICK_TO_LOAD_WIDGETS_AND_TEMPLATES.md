> **Phase 6.6 note:** the Live Stream template and all widget streaming/host-stream syntax were removed. The list below documents what Phase 6.3 originally contained; it is not the current template catalog.

# Weave Phase 6.3 — Click-to-load widgets + built-in source templates

## Core activation rule

A remote profile may advertise a widget's inert metadata (label, geometry, source hash and public blob record key), but Weave does **not** fetch the widget package simply because the page was opened or scrolled into view.

The visitor must tap the widget placeholder first. Only then does Weave:

1. fetch the source-only widget package blob,
2. verify the blob/package framing and pinned source hash,
3. compile the received source locally,
4. pass the locally-produced VWB1 bytecode through the independent verifier,
5. start the widget runtime.

Navigating away and returning still requires a tap before a widget runs. A compiled program may remain in an in-memory cache so the later tap can avoid another network fetch, but cached code is never auto-started just because the page appears.

## Publishing

Profile publishing now uploads local widget packages before publishing the profile document, similar to media publishing. The published widget blob contains source/manifest/provenance only. Executable bytecode is not put on the DHT.

The profile element stores the returned public blob record key and the pinned source hash. If the author later accepts a changed local widget source, the element is deliberately changed back to a `local-widget:` reference so the next Publish must upload a fresh source package; this prevents a new source hash from accidentally pointing at an old public package.

## Built-in templates

The Add Widget chooser now includes a built-in template drop-down. Selecting a template creates an ordinary local source widget with a fresh widget id, then runs it through the same compiler and verifier as user-authored code. Built-ins receive no privileged bytecode path.

Included in this draft:

- Clock — working V1 timer/time example.
- Live stream — working V1 host-stream surface; playback is viewer initiated.
- My playlist — host-capability scaffold using the safe `host:playlist` handle.
- Chess — shared page-session UI scaffold. Full two-person state needs the planned shared-state host capability.
- Flappy — local game layout scaffold. Full play needs planned mutable state/arithmetic/motion language operations.
- Poll — local preview plus planned host poll transport.
- Quick quiz — working local V1 button/event example.
- Guestbook — UI scaffold for a future narrow guestbook host capability.

`Host capability scaffold` and `Language feature scaffold` labels are intentional. The templates are shipped now so the package/selection/security lifecycle can be exercised without pretending V1 already contains networking or game-physics primitives.

## Security boundary

A malicious publisher still cannot choose executable instructions for another person's ordinary Weave client. The receiving client downloads source only and compiles it locally. Unknown/custom widget formats or malformed packages are rejected. Raw sockets, DHT access, filesystem, camera, microphone and arbitrary native APIs remain absent from the widget VM.
