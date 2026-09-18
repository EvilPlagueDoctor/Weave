# Weave Phase 6.6 — Public Widget Networking V1

Phase 6.6 adds a deliberately narrow public-networking capability to the source-only Weave widget runtime. It does **not** give widget code raw Veilid, DHT, mailbox, socket, Android, or Internet access. Network data is treated as hostile and is validated by the receiving Weave client against its own locally compiled copy of the widget.

## Streaming removed

The previous experimental media/live-stream widget surface has been removed from the active widget language and runtime for now. This includes the stream node/capability, editor controls, verifier/runtime support, and the Live Stream sample template. Streaming can be redesigned later as a separate, explicitly reviewed capability.

## Opt-in networking in widget source

A widget is offline unless its source explicitly declares:

```text
online = public
```

Without that declaration, network actions fail compilation/verification and the runtime does not expose the public-widget-network host.

A widget may optionally request that the same public signal also be copied to the widget publisher's normal mailbox:

```text
mail_copy = true
```

This is intended for things such as surveys/guestbooks where the publisher may be offline when a response is made.

## Declared inputs

Widgets may declare at most **16** input channels. A single logical network event may submit at most **2** input values.

Examples:

```text
input x = number(1, 8)
input y = number(1, 8)
input resign = button
```

Buttons count as inputs. Received values are checked against the receiving client's locally compiled definitions; a modified sender cannot invent an undeclared input or submit a value outside the declared range.

A tap may combine up to two `network.send(...)` actions, plus an optional text action, into one logical network event.

## Text

Text is a separate inert data path:

- maximum **1 KiB (1024 UTF-8 bytes)** per message;
- a receiver reads at most **5** recent text messages from one participant Session DHT for that widget;
- text is displayed/handled as plain UTF-8 data only;
- text is never passed to the widget compiler, parser, evaluator, or markup engine;
- disallowed control characters and malformed UTF-8/wire encodings are rejected.

Pasting widget source, Kotlin, JSON, or any other code into a text message does not make it executable.

## Two DHT roles

### Widget Data DHT

A long-lived publisher-owned data store associated with a particular widget instance. It is separate from the immutable source package/hash. It is intended for durable information such as aggregate poll results or publisher-updated widget data.

Every locally published widget can have a Widget Data DHT, including offline widgets. Reading publisher data is not itself treated as public participant networking.

### Widget Session DHT

A participant-owned temporary activity store used for public widget events, invitations, responses, and recent text. Session DHTs rotate automatically (currently after 24 hours).

When rotated, the old Session DHT remains locally remembered/readable for **7 days**. After that retention period Weave overwrites used event/message slots with a machine-readable tombstone containing:

> No ones watching anymore, Lets Disco party!

and then forgets the local key/reference.

The new Session DHT does not publicly point back to the retired one, so rotation does not intentionally create a permanent public correlation chain.

## Public discovery

A participant writes the canonical event to their own Widget Session DHT, then publishes a short-lived public `ServiceRequest` signal containing the information needed to locate that exact event. The discovery signal has a maximum lifetime of approximately **one hour**.

The signal points to the participant's Session DHT and includes the exact event hash/sequence. A receiving client reads and validates the exact event before accepting it.

This supports:

- turn-based games;
- polls/surveys;
- guestbooks;
- spectators following public game/session activity;
- offline publishers receiving short-lived public responses.

If `mail_copy = true`, Weave can additionally place the same pointer in the publisher's normal mailbox for longer-lived recovery/aggregation.

## Event locking and continuity

Events contain bounded typed fields including widget source/version identity, widget instance identity, session identity, sequence number, previous-event hash, event kind, inputs/text, and invitation/session fields where applicable.

A receiver accepts an event by its exact canonical event hash. If the sender later rewrites its DHT slot, that does not retroactively change the already accepted event/hash on the receiver.

Sequence numbers and previous hashes are encoded to support continuity/replay detection. Phase 6.6 does not claim blockchain-style consensus and does not yet expose full game-rule validation to widget source.

## Invitations

The Kotlin networking layer contains the public three-step session handshake primitives:

1. inviter publishes an invitation and its temporary Session DHT;
2. another participant publishes an acceptance tied to that invitation and their Session DHT;
3. the inviter publishes the final acceptance acknowledgement.

This prevents a simple two-party game from treating multiple simultaneous acceptances as equally final. The low-level invitation/accept/ack methods are implemented in the host networking layer; the full receive-side/session UI syntax is not yet exposed to ordinary widget source.

## Strict wire format

Phase 6.6 uses a versioned, length-delimited canonical binary format. It does not use NUL-terminated fields.

The decoder checks bounds/types before allocating or accepting data. Examples include:

- exact protocol/version/type values;
- bounded string lengths;
- strict IDs/hashes/session IDs;
- numeric fields decoded as numeric fields rather than coerced from strings;
- maximum two inputs per event;
- declared input IDs only;
- local input range checks;
- maximum 1 KiB text;
- canonical re-encoding checks;
- no trailing bytes;
- no malformed/unsupported control characters.

Malformed records are rejected rather than repaired/coerced.

## Abuse limits

The host networking layer currently applies additional local limits, including bounded publication/read rates. A widget can write frequently to its own Session DHT, but the receiving client decides what it reads, validates, and locks.

No widget receives raw DHT keys/functions beyond opaque host-managed handles/operations needed for this protocol.

## Source-only security model remains

Remote widgets remain click-to-load:

1. opening a page loads only inert widget placeholder metadata;
2. the viewer taps the widget;
3. Weave fetches the source-only widget package;
4. source/hash/version are checked;
5. source is compiled locally;
6. locally generated bytecode is independently verified;
7. only then may the widget run.

Published executable bytecode is not trusted as the authority.

## Built-in templates

The active built-in set is now:

- Clock
- My Playlist (non-streaming scaffold)
- Chess (public-network scaffold)
- Flappy (language scaffold)
- Poll (public networking)
- Quick Quiz
- Guestbook (public networking + inert text input)

The old **Live Stream** template has been removed.

## Current Phase 6.6 limitations

This is still Public Widget Networking **V1**. In particular:

- the Kotlin layer has invitation/accept/ack/read primitives, but ordinary widget source does not yet have receive-side network event handlers/session-state syntax;
- Chess can publish its declared network inputs but is still a scaffold for a fully interactive two-player UI;
- the publisher Widget Data DHT exists and receives safe aggregate data, but a general schema-driven Data-DHT read language has not yet been exposed to widget source;
- reaction-time/direct networking is not implemented.

## Reserved future method — not implemented

A possible future direct/private session design is reserved conceptually, where a ServiceRequest/Session DHT can advertise a temporary route/handshake path for lower-latency interaction. Phase 6.6 does **not** implement or expose this method.
