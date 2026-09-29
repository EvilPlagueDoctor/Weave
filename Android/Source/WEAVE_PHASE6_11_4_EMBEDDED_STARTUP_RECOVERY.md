# Weave Phase 6.11.4 — Embedded startup recovery

This build addresses the two independent startup failures exposed by the Phase 6.11.3 diagnostic log.

## 1. Older VeilKnit account: stale/lost `weave.v1` credential

Observed failure:

- `finish_authentication` returned `authentication_failed: invalid app authentication proof`.
- Weave removed its stale local secret and attempted the normal first-run registration path.
- The daemon still had `weave.v1` registered, so `request_app_registration` returned `app_already_registered` forever.

Fix:

- Added an Android embedded-only JNI recovery function.
- If bundled Weave has no usable credential but the active VeilKnit account already has `weave.v1`, the daemon rotates only that app credential in-process and returns the new secret + generation to Weave.
- Weave stores the replacement secret in the correct per-profile secure credential slot and retries authentication.
- This recovery hook is not exposed through the external protocol-v3 socket transport.

## 2. Fresh account: App Directory starved by optional startup budget

Observed failure:

- `create_app_store` succeeded and produced a valid profile-store root.
- Every `register_app_root` failed immediately with `service_unavailable: app directory service is unavailable`.

Root cause in the merged startup sequence:

The daemon treated App Directory initialization as one of several optional DHT startup tasks sharing a 30-second budget with mailbox and walker startup. On a slower fresh-account boot, earlier services could consume the budget. The daemon then continued to READY with `app_directory=None`, even though Weave requires App Directory to publish its profile root.

Fix:

- App Directory initialization now runs before the shared optional-services budget begins.
- It receives two dedicated attempts, each bounded to 30 seconds, with a short retry delay.
- Existing App Directory state is reused when present; fresh accounts can create it without mailbox/walker consuming its time allowance first.
- Startup emits `Preparing application services...` while this work is happening, so Weave's daemon-loading UI can display meaningful progress.

## Expected startup behavior

Older account with stale Weave credential:

1. VeilKnit account logs in.
2. Embedded Weave authentication detects the stale/lost credential.
3. Status briefly shows `Recovering Weave authorization for this VeilKnit account...`.
4. The in-process credential is rotated and saved.
5. Weave authenticates and proceeds to profile-store setup.

Fresh account:

1. Main DHT comes online.
2. App Directory is opened/created before optional mailbox/walker budget starts.
3. Embedded local API becomes READY.
4. Weave creates/reuses `weave-profile-page-v1`.
5. `register_app_root` should now succeed.
6. Normal Weave onboarding/profile loading continues.

Phase 6.11.3 startup diagnostics remain enabled so any remaining failure should still produce the focused startup trace and clipboard report at profile-storage retry 5/5.
