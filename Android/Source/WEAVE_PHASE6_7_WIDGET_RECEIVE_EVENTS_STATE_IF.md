# Weave Phase 6.7 — Widget Receive Events, State, and `if` / `else`

Phase 6.7 extends the source-only widget language so a public-network widget can react to already-validated network events without gaining access to raw DHTs, mailboxes, sockets, identities, or protocol bytes.

## Security boundary

Remote bytes are parsed and checked by Kotlin first. Before an event is visible to widget source, Weave verifies the exact widget source hash and instance ID, canonical event encoding/hash, session information, event continuity, declared input names, input count, numeric ranges, text limits, and protocol-specific fields. The widget VM receives only a small typed `WidgetHostEvent`.

`network.accept` never means “accept whatever is currently in that DHT.” It acknowledges the exact event hash Kotlin delivered to that handler. A later rewrite of the sender's Session DHT cannot retroactively change an accepted event.

## Local state

A widget can declare bounded in-memory runtime state:

```text
state turn = 1
state ready = false
state label = "Waiting"
```

V1 state types are integer number, boolean, and UTF-8 text. State names follow the same small identifier rules as other widget symbols. State is runtime-local in this pass; it is not a general persistent storage API and it does not expose host/device storage.

Limits include 64 state variables and 1 KiB for a text-state value.

## Expressions and `if` / `else`

Example:

```text
on tap(next):
    if count < 8:
        count = count + 1
        status.text = "Square " + count
    else:
        status.text = "At the end"
```

The expression engine is deliberately small. It supports integer, boolean, and text literals; local/event variables; parentheses; `!`/`not`; integer `+ - * / %`; comparisons; equality/inequality; boolean `&&`/`and` and `||`/`or`; and text concatenation with `+`.

There is no reflection, eval, dynamic source loading, host-object traversal, function calling, networking call hidden inside an expression, or arbitrary allocation. Expressions have byte/token limits and `if` nesting is capped.

## Incoming input proposals

```text
on network.input(x, y):
    if session_ready == true:
        network.accept
    else:
        network.reject
```

The handler signature must name one or two inputs already declared by this widget. The handler only runs for an incoming proposal containing that exact set of input IDs. Numeric values have already been checked against the locally compiled declaration.

Button inputs arrive as a read-only boolean `true` event value; numeric inputs arrive as read-only integers.

If the handler does not explicitly accept an input, it is not committed by the widget. `network.reject` drops that pending proposal on this client.

## Committed events

```text
on network.committed(x, y):
    status.text = "Move committed: " + x + "," + y
```

A committed event represents exact accepted history, not an untrusted proposal. The sender sees its submitted event become committed after the peer's `ReadAck`; the accepting peer receives the same committed values once its acknowledgement succeeds.

This makes game display/state updates cleaner: validate in `network.input`, then update the durable local game view in `network.committed`.

## Inert text

```text
on network.text(message):
    chat.text = message
```

`message` is read-only plain UTF-8 data. It is capped at 1 KiB and comes through the existing five-message-per-sender/widget limit. Text is never fed to the widget compiler, parser, markup engine, or command interpreter.

## Invitation/session events

```text
on tap(find_player):
    network.invite

on network.invite:
    accept_button.visible = true

on tap(accept_button):
    network.accept_invite

on network.invite_accepted:
    status.text = "Opponent accepted"

on network.session_ready:
    status.text = "Game ready"
```

Invitation tokens are opaque runtime handles held by Kotlin. Widget source never sees a DHT key, peer identity, event hash, or raw token. `network.accept_invite` / `network.decline_invite` can be used directly from the incoming invite handler or from a later tap handler while the runtime retains the pending opaque invitation.

The existing three-step protocol remains underneath:

1. inviter posts Invite and Session DHT;
2. accepter posts Accept referencing the invitation/session;
3. inviter posts final AcceptAck, at which point the paired session is ready.

## Network sends remain user-driven

Receive handlers cannot automatically call `network.send(...)`, `network.text...`, or open a new invitation. Those operations remain restricted to tap handlers so a hostile incoming event cannot create an automatic send loop.

Phase 6.8 supersedes the original one-outstanding-action rule: a paired session may now have up to five unacknowledged input actions in flight, while each action still carries at most two declared inputs.

## Bundled Chess reference widget

The Chess template now uses the real receive-side language and host path:

- opens/accepts a public invitation;
- waits for `network.session_ready`;
- sends declared `x` and `y` inputs;
- accepts/rejects incoming proposals;
- uses `network.committed(x, y)` to build a FROM/TO move transcript;
- handles a declared `resign` button input;
- uses local state and `if` / `else` for cursor/session/move phase.

This is a functioning turn-based networking/reference flow, but it is not yet a full chess rules engine: it does not validate legal chess moves or animate/mutate the displayed piece board. Those are widget-program features rather than missing transport primitives.

## Bytecode/runtime

This language revision uses local bytecode `VWB3` / runtime version 3. Published packages remain source-only. On receipt Weave re-hashes source, compiles locally to VWB3, independently verifies it, derives capabilities from that verified program, and then executes it. Legacy VWB1/VWB2 package bytecode is never executed by the V3 runtime; compatible source is recompiled locally.

## Still deliberately absent

- raw DHT/mailbox access from widget source;
- sockets, HTTP, IP/device information, filesystem, native APIs;
- streaming/media-network widget primitives;
- direct/private Widget Networking Method B;
- arbitrary functions/classes/imports/eval;
- high-resolution timers;
- persistent arbitrary widget storage;
- spectator-session selection UI/source syntax (the public event records remain spectator-readable at the protocol layer).
