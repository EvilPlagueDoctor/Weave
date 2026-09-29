# Weave Phase 6.11.5 — Embedded VeilKnit service startup fix

## Problem
Phase 6.11.4 gave App Directory its own startup window, but mailbox, walker, initial application advertisement, and the distributed lexical library still shared one 30-second `OPTIONAL_DHT_STARTUP_BUDGET` stopwatch.

On a normal mobile DHT cold start, making a record routable can take roughly ten seconds. Mailbox initialization may create/recover multiple DHT records. It could therefore consume the whole shared window by itself. Every service after it then received a zero-length timeout and was permanently omitted from the `LocalApiContext` for that login session.

Typical log signature:

- App Directory ready
- several `Creating multi-owner DHT` / `Record is ready` pairs
- `[mailbox] startup exceeded 29s`
- `[walker] optional DHT startup budget exhausted`
- `[user_dht] optional DHT startup budget exhausted`
- `[lexical] optional DHT startup budget exhausted`
- later API calls fail with `service_unavailable`

## Fix
The shared stopwatch was removed. Each service now owns an independent bounded startup timeout:

- Mailbox: 90 seconds
- Network walker: 45 seconds
- Initial application advertisement: 30 seconds
- Distributed lexical library: 45 seconds

Each timeout is only a maximum. Successful startup proceeds immediately.

This keeps the shutdown/startup safety property (a truly wedged DHT cannot block forever) while preventing one healthy but slow subsystem from starving all later subsystems.

## Expected cold-start log
You should now see entries similar to:

```
[mailbox] Starting mailbox services (timeout=90s)...
Mailbox controller started.
[walker] Starting network walker (timeout=45s)...
[user_dht] Publishing initial application advertisement (timeout=30s)...
[lexical] Starting distributed lexical library (timeout=45s)...
[gui] READY
```

The loading screen can remain visible longer than 6.11.4 on a cold account because READY is no longer emitted while required handles are being deliberately starved by the old shared deadline.

## Version
- Android versionCode: 33
- versionName: `0.10.2-service-startup-fix`
