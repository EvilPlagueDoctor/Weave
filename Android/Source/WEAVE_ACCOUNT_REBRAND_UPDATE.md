# Weave Android – account switching + pre-release identity reset

## Connection/account changes

- Weave now reads the daemon's `getDaemonStateJson()` before authenticating.
- It waits for `ready=true` plus non-empty `profile_id` and `daemon_instance_id`.
- Daemon app credentials are stored separately per `profile_id`.
- A changed `daemon_instance_id` invalidates the old session and causes an automatic reconnect.
- A changed `profile_id` also clears account/network-derived caches before reconnecting.
- Revoked/rotated credentials are removed only for the affected profile and go through normal approval again.
- This requires a daemon build that exposes `profile_id` and `daemon_instance_id` (VeilKnit daemon consumer/account-aware v5 or newer).

## Intentional pre-release identity break

Because Weave has not been publicly released, this build intentionally does not preserve VeilySocial compatibility.

Changed examples include:

- Android application/package: `app.weave`
- daemon application id: `weave.v1`
- display name: `Weave`
- APK output: `Weave-<variant>.apk`
- profile DHT store name: `weave-profile-page-v1`
- gossip context: `weave_gossip`
- profile content type: `application/x-weave-vspf-text;version=3`
- comment store names and local preference names
- Kotlin package names and old `Veily*` internal type/function names
- user-visible and diagnostic references to VeilySocial

The old application approval and old app-owned DHT stores are therefore intentionally treated as belonging to a different prototype application.

## Local storage/encryption audit

The following Weave data is currently stored in Android's private app sandbox but is **not cryptographically encrypted by Weave itself**:

- `files/profiles/*`
- `files/comments.json`
- `files/following.json`
- `files/media/*`
- `files/widgets/*`
- discovery/onboarding/daemon SharedPreferences

Android may apply device/file-based encryption at the OS level, but that is separate from VeilKnit account encryption.

The daemon's `UserAuth::write_user_encrypted` / `read_user_encrypted` primitives are per-user/per-network-profile and encrypted, but they are currently an internal daemon API. The app-facing `ManageOwnStorage` API creates DHT/network stores; it is not a generic encrypted local-file service. App-store catalog/writer metadata is encrypted locally by the daemon, while the application DHT payload itself is network storage.

A clean future extension would be an authenticated, app-namespaced local encrypted data API backed by `UserAuth`, with explicit size/quotas and binary support. Moving Weave profiles/comments/follows/media/widgets to that service should be done deliberately because it changes offline behavior and asset-sharing semantics; this build does not silently migrate or delete those existing local files.
