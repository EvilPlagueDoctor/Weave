# Weave Groups v2 — Phase 2 Witness Patch 1

## Why this patch exists

The first Phase-2 three-phone transport test showed that canonical event creation was healthy and that direct/mailbox sends were accepted by the daemon, but the public witness ServiceRequest failed because the daemon's fully serialized request was 1061 bytes while the maximum is 1024 bytes.

The signed Groups-v2 event itself was not intrinsically too large. The problem was representation overhead: the event was converted to JSON inside a JSON transport packet, then Base64/serialized again by the ServiceRequest API.

## Change

Public witness transport now uses `GroupEventWitnessCodecV2`, a compact binary representation of the same signed event fields. It does not change the event signature format or event identity. Signature verification still uses `SignedGroupEventV2.unsignedCanonicalBytes()` exactly as before.

Authenticated Direct and PrivateMailbox transports intentionally retain `GroupEventTransportPacketV2` so this patch does not change the two paths that still need timing/receipt observation.

For member-only groups, the compact witness is AES-256-GCM encrypted directly, instead of encrypting a JSON transport packet.

## Size

A realistic normal post event used by the local round-trip test encoded as:

- plain compact witness: 415 bytes
- encrypted member-only witness: 448 bytes

A hard local ceiling of 700 payload bytes is applied before calling `publish_service_request`, leaving substantial headroom for the daemon's own ServiceRequest metadata/Base64 serialization under its 1024-byte limit.

## New diagnostics

Sender:

`[groups-v2] WITNESS_ENCODE event=... compact_bytes=... payload_bytes=... encrypted=false`

Receiver:

`[groups-v2] WITNESS_DECODE event=... payload_bytes=... encrypted=false`

The existing transport result remains:

`TRANSPORT_PUBLIC_WITNESS_QUEUED` or `TRANSPORT_PUBLIC_WITNESS_FAILED`.

## Compatibility

The receiver still accepts the short-lived Phase-2 JSON plain-witness form if one is already in flight. Old encrypted Phase-2 witness packets are intentionally not preserved; the project is unreleased and private witness interoperability has not yet been relied upon.

## Next test

Repeat the Phase-2 Test 1 with:

- Phone A: ordinary poster
- Phone B: Original owner or Claimer
- Phone C: spectator

Wait at least 2–3 minutes before copying diagnostics. Expected:

1. A logs `WITNESS_ENCODE`, ideally around 400–500 bytes.
2. A logs `TRANSPORT_PUBLIC_WITNESS_QUEUED` rather than the 1024-byte failure.
3. C logs `WITNESS_DECODE`, signature validation, content hash validation, and `EVENT_STORED_SPECTATOR`.
4. B may receive Direct, PrivateMailbox, PublicWitness, or several of them; they must collapse into one event and one branch decision.
5. If Direct/PrivateMailbox remain absent after several minutes while PublicWitness succeeds, investigate those daemon delivery paths separately.
