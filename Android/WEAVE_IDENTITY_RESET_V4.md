# Weave Android — canonical identity reset (v4)

This build applies the final naming decision: the application is **Weave**.
`VeilKnit` names the daemon/network/ecosystem that Weave uses; it is not part of the app name.

This is intentionally **not backwards compatible** with the older `VeilKnit Weave` prototype identity.
The important identity values are now:

- display name: `Weave`
- Android namespace/application id: `app.weave`
- daemon application id: `weave.v1`
- APK output: `Weave-<variant>.apk`
- bootstrap credential preferences: `weave_daemon`
- per-profile Android Keystore alias prefix: `weave.bootstrap-credential.v1`
- discovery preferences: `weave_discovery`
- profile content type: `application/x-weave-vspf-text;version=3`
- profile DHT store: `weave-profile-page-v1`
- gossip context: `weave_gossip`
- explicit Gallery export folder: `Pictures/Weave`

## Consequences of the intentional break

The daemon sees `weave.v1` as a new application, so the first connection requires a fresh approval.
The new daemon private-storage vault is also separate from the old `veilknit.weave.v1` vault.
The Android package `app.weave` is a new install identity rather than an in-place upgrade of `com.veilknit.weave`.
Network/profile objects using the old `application/x-veilknit-weave-vspf-text` content type are not treated as the new Weave identity.

No compatibility migration from the old prototype identity is performed by this build.
