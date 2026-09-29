# Weave Phase 6.11.3 — Embedded startup diagnostics

This diagnostic build adds focused logging around the embedded VeilKnit → Weave startup boundary.

## Profile-store diagnostics

Each of the five profile-store attempts now records:

- embedded daemon connection stages
- active daemon/main-DHT identity (shortened in the focused trace)
- `list_app_stores` begin/success and returned store count
- existing store names / shortened IDs / shortened record keys
- `create_app_store` begin/success when the Weave profile store does not exist
- selected profile store ID/root
- `register_app_root` begin/success
- comment-store initialization as a separate non-fatal stage
- exception class/message, cause chain, and top stack frames for each failed attempt
- retry delay

The existing RPC breadcrumb log is also included in the final diagnostic report. It records metadata only and does not include authentication secrets or request/response payloads.

## Automatic clipboard capture

If profile storage reaches failed attempt 5/5, Weave automatically copies a `Weave embedded VeilKnit startup diagnostic` report to the Android clipboard before the reconnect loop begins. A Toast confirms `Weave startup log copied to clipboard`. The reconnect banner also mentions that the startup log was copied.

Paste that clipboard contents into the bug report/chat after reproducing the issue.
