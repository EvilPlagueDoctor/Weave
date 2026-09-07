# Weave Android — encrypted account storage migration

This package migrates Weave's user-derived local state to the daemon-owned private application vault.

Migrated categories include profile data, comments, following, widgets, onboarding state, discovery/preferences, media metadata/indexes, and local media. User-owned/imported media is intended to be persistent; disposable remotely downloaded media uses encrypted cache storage.

Explicit Gallery export remains outside the private vault because it is an explicit request to make media visible outside Weave.

The daemon bootstrap credential cannot live behind an API that itself requires daemon authentication, so it is stored using Android Keystore AES-GCM. Credential encryption keys are profile-specific rather than a single shared device/account key.

Legacy plaintext migrations only remove old plaintext after the encrypted write succeeds. Account/profile switching clears decrypted in-memory state and uses generation checks so stale asynchronous work from the previous account cannot commit into the newly active account's vault.

A full Android Gradle build was not available in the packaging environment. Static source checks were performed, but the user's local Android build remains the definitive compiler/runtime validation.
