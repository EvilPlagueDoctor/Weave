# Weave Phase 6.8 — Widget Multi-Action Window and Host Close

## Changes

- Public widgets may declare up to **32** network inputs. A button input still counts as one declared input.
- A single `network.send(...)` still carries at most **2** declared inputs.
- A paired session may now have up to **5 unacknowledged input actions** in flight. This removes the old one-action round-trip requirement and allows patterns such as chess FROM-square then TO-square to be submitted back-to-back.
- The five actions are still independent canonical events with independent hashes and acknowledgements; this is a bounded in-flight window, not an arbitrary packet stream.
- The host rejects a sixth paired input action until at least one earlier action is acknowledged. Reservation is atomic so rapid taps cannot race past the five-action limit.
- The bundled Chess template now gives immediate local feedback after the FROM action and lets the TO action be sent without waiting for the FROM acknowledgement.
- Every activated profile widget has a host-owned **X** button. It is outside widget source and cannot be hidden by the widget.
- X closes the local `WidgetNetworkHost`, clears pending invites/actions/session pairing, removes the runtime from Compose, and returns to the inert click-to-load placeholder. Reopening creates fresh VM state. The close operation does not rewrite or erase already-published DHT history.

## Limits retained

- `online = public` remains required for networking.
- Maximum inputs carried by one event: 2.
- Maximum plain-text message: 1 KiB.
- Maximum recent text messages read per sender/session/widget: 5.
- Network text remains inert UTF-8 and is never compiled or interpreted.
- Incoming values are validated by Kotlin against the locally compiled widget definition before handlers run.
- Streaming remains removed.
