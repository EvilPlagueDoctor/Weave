# Weave Phase 6.11.1 — Embedded VeilKnit connection repair

This repair targets the first-device report from Phase 6.11 where the embedded VeilKnit account
could authenticate and reach Weave's Create Profile page, then approximately ten seconds later
fall into `Connection lost; retrying…` indefinitely.

## Changes

- Weave is no longer marked `connected=true` immediately after `get_identity`. The normal profile
  UI stays behind the loading gate until the Weave profile store/application layer is ready.
- Profile-store startup now retries in the same authenticated daemon session instead of immediately
  tearing the whole session down for a transient DHT/store initialization failure.
- Gossip, custody recovery, group-directory publication, and live-stream startup are treated as
  recoverable application features. A failure is logged/deferred rather than being mislabeled as a
  lost daemon connection.
- Application-message, group-intake, and public-widget streams are checked every five seconds and
  are resubscribed if they close.
- The Rust local API now classifies *all* streaming request types as subscriptions:
  `subscribe_events`, `subscribe_messages`, `subscribe_service_requests`, `subscribe_gossip`, and
  `subscribe_streams`. Service-request streams are therefore not forced through the normal 150 s
  control-request timeout.
- The embedded Tokio subscription transport now mirrors the old LocalSocket behavior by keeping
  the request/write side open for the life of the stream instead of half-closing it immediately.
- Weave's daemon identity check now prefers the active Rust embedded context's profile ID over
  rereading `daemon_endpoint.json` once per second. The file remains as a fallback.
- Login/sign-up Username, Password, and Confirm Password fields now explicitly use the VeilKnit
  dark-field/light-text palette, including the password visibility icon.

## Expected startup behavior

1. VeilKnit sign-in/sign-up screen.
2. Persistent foreground notification starts as before.
3. VeilKnit startup/loading information is shown.
4. After the daemon is ready, Weave displays `VeilKnit connected; preparing Weave profile storage…`
   while its own app storage finishes.
5. Only then does Create Profile / the saved Weave profile appear.

The Rust core remains a separate Rust module. This patch only changes the embedded transport and
Weave-side lifecycle/error isolation around it.
