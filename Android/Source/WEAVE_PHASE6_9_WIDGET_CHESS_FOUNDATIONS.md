# Weave Phase 6.9 — Widget Chess Foundations

Phase 6.9 adds the bounded language/runtime features needed to build a real turn-based Chess widget without hard-coding Chess into Android/Kotlin.

## Security boundary

The widget still never receives Android Views, Compose nodes, sockets, raw DHT keys, mailbox objects, filesystem paths, device identifiers, or cryptographic keys. Source is published; the receiving client compiles it locally to VWB4, independently verifies that bytecode, and Kotlin owns rendering/network transport.

Every value remains bounded before it reaches widget code. Network records are strict canonical length-delimited records. Malformed records, unknown fields/inputs, out-of-range numeric inputs, invalid UTF-8/control characters, oversized text, wrong widget/source/instance IDs, broken sequence/hash chains, and invalid grouped-action metadata are rejected below the VM.

## Bounded arrays

Numeric arrays are now available for compact game state:

```text
state board = array(64, 0)

board[0] = 4
board[(y - 1) * 8 + (x - 1)] = piece
```

Limits/behavior:

- maximum 128 array elements
- arrays contain signed numeric values only
- indices are bounds checked at runtime
- array size is fixed after compilation
- arrays cannot contain strings, objects, handles, or native references

The Chess template uses a 64-cell board with compact piece codes.

## Small helper functions

Widgets may declare bounded helper procedures:

```text
function move_piece(fx, fy, tx, ty):
    board[(ty - 1) * 8 + (tx - 1)] = board[(fy - 1) * 8 + (fx - 1)]
    board[(fy - 1) * 8 + (fx - 1)] = 0

call move_piece(2, 2, 2, 4)
```

Current limits:

- maximum 32 helper functions
- maximum 8 numeric parameters per function
- maximum call depth 8
- recursion/cyclic call graphs are rejected by compiler and verifier
- no return values yet
- helper functions cannot perform network actions

This keeps reusable game logic possible without turning functions into an escape hatch.

## Dynamic UI-property assignment

Runtime expressions can now update a small host-owned property set. The widget never receives a TextView/Compose object.

Examples:

```text
status.text = "Player " + network.my_player
send_move.enabled = my_colour == turn_colour
status.text_color = "#7D3440"
status.background = "#FFF4F5"
```

Supported dynamic properties are deliberately limited to text, visibility, enabled state, background colour, and text colour. The outer widget bounds and native UI objects remain controlled by Weave.

Runtime text written to a node is UTF-8 bounded/truncated to the widget string limit; text state remains separately capped. Dynamic colours are parsed/represented as validated numeric ARGB values.

## Player-slot metadata

Once an invitation handshake completes, Kotlin assigns immutable session slots:

- inviter = Player 1
- accepter = Player 2

Widget expressions can read:

```text
network.my_player
network.event_player
```

The widget does not receive the participant's main DHT, Widget Session DHT, IP, account identifier, or any other raw identity value.

`network.event_player` identifies which slot produced the current committed/input/random event. This is separate from game-specific roles such as White/Black.

## Grouped multi-step actions

A turn can now be sent as one bounded logical action rather than requiring a round trip after each tap:

```text
network.begin_action
network.send(x=from_x, y=from_y)
network.send(x=to_x, y=to_y)
network.end_action
```

Rules:

- maximum five steps in one grouped action
- each step is still limited to at most two declared network inputs
- all steps in an action must use the same input-name set
- every step is independently canonicalized, hashed and stored
- the receiver sees the action only after the complete declared step set has arrived
- `network.accept` acknowledges every exact event hash in that action
- changing a sender DHT afterward cannot change the action the receiver accepted

Receive syntax:

```text
on network.action(x, y):
    if network.action_count == 2:
        network.accept
    else:
        network.reject

on network.action_committed(x, y):
    call move_piece(
        network.action.x[0], network.action.y[0],
        network.action.x[1], network.action.y[1]
    )
```

`network.action.<input>[index]` is a read-only bounded event array supplied by Kotlin.

## Commit/reveal shared randomness (“dice”)

Paired online widgets now negotiate shared randomness without trusting either participant's RNG result by itself.

1. Each client creates a fresh private 32-byte random secret.
2. Each publishes only a commitment hash first.
3. The two Widget Session DHT identifiers determine deterministic reveal order. Kotlin compares the first four hexadecimal characters of a SHA-256 digest of each Session DHT, with a full-hash tie-break. The raw DHT identifiers are never exposed to widget source.
4. The first side reveals only after both commitments are visible.
5. The second side verifies that reveal, then reveals its own secret.
6. Both clients verify both commitments and independently derive the same shared seed from both secrets and the invitation/session context.
7. Roll results are derived deterministically from the shared seed, roll index and requested number of sides.

The first allowed roll belongs to the participant selected by the deterministic DHT ordering. Later rolls alternate Player 1/Player 2. A client cannot request a roll when it is the other player's scheduled roll.

Language hooks:

```text
on network.random_ready:
    network.roll(2)

on network.roll(2, toss):
    if toss == 1:
        ...
```

`network.random_first_player`, `network.roll_index`, `network.roll_sides`, `network.my_player`, and `network.event_player` are read-only host metadata.

A participant can abort/refuse to reveal, but cannot wait for the other secret and then change its already-committed secret to force a preferred result.

## Chess reference template

The bundled Chess reference now demonstrates:

- 64-cell bounded board array
- compact signed piece codes
- board setup helper
- move helper
- invitation/accept/final-ack session setup
- Player 1/2 metadata
- commit/reveal two-sided toss for White/Black
- dynamic status/button state
- FROM + TO sent as one two-step action
- exact-action acceptance/commit
- resignation as a separate declared button input

It is still a **foundations/reference Chess widget**, not yet the final Chess implementation. Full move legality, check/checkmate, castling, en passant, promotion UI, repetition/50-move rules, and a true 64-square interactive board are the next game-specific layer. Those rules can now live in inspectable widget source instead of being privileged Kotlin code.

## Current hard limits relevant to this pass

- source: 64 KiB
- visual nodes: 96
- events: 64
- actions/event: 32
- instruction budget/event: 256
- scalar/array states: 64 total
- array length: 128
- helper functions: 32
- helper params: 8
- call depth: 8
- if depth: 8
- expression: 512 bytes / 128 tokens
- network inputs declared: 32
- inputs per network step: 2
- grouped action steps: 5
- dice sides: 2..1000
- network text: 1 KiB per message, five messages exposed per sender/widget context

## Bytecode/package model

The local bytecode revision is `VWB4` / runtime version 4. Published widget packages continue to contain source/manifest/provenance only. Remote bytecode is not trusted or executed. The receiving copy of Weave compiles source locally and independently verifies the resulting VWB4 program before activation.
