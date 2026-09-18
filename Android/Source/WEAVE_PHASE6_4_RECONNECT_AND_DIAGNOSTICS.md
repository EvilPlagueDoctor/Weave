# Weave Phase 6.4 — Editor reconnect resilience + persistent breadcrumbs

This pass targets the intermittent profile-editor failure where the editor could disappear back to **Me**, briefly show the old star starter profile, and sometimes coincide with daemon/API trouble that was difficult to capture in logcat.

## 1. Temporary daemon loss no longer means “load the starter profile”

`EditorState.onVaultDetached()` now treats a detached private vault as a **temporary storage outage**, not an account/profile reset.

During a reconnect Weave keeps in memory:

- the current `ProfileDocument`
- current page
- basic/advanced editor destination
- selected element/tool/mode
- undo/redo/editor UI state

The star starter profile is therefore not substituted simply because the daemon Binder/session disappeared for a moment.

When the vault attaches again:

- if the same VeilKnit profile returned, the in-memory editor is retained;
- if edits attempted to persist while disconnected, one deferred save is retried;
- if a genuinely different VeilKnit profile attached, that account's encrypted profile is loaded and the account boundary is respected.

Profile loading now distinguishes a confirmed missing profile from an I/O/decode failure. A read/decode failure is surfaced in a **Profile storage needs attention** banner and logged instead of being silently treated as a valid starter-profile load.

## 2. Navigation survives transient reconnects

`SocialUiState` now has an explicit `connected` flag. The last confirmed `mainDht` is kept while reconnecting rather than being cleared immediately.

That prevents `WeaveApp` from destroying `WeaveShell` (and therefore its `rememberSaveable` navigation stack) every time the daemon session reconnects.

If the user was in Basic or Advanced profile editing, that editor remains composed. A small **Reconnecting to VeilKnit…** banner is shown instead of replacing the whole app with the connection gate.

The connection gate is still used when Weave has no known account identity yet (startup / first connection).

## 3. Deferred profile persistence

If an editor commit happens while the private vault is unavailable, the document remains in memory and the save is marked pending rather than being converted into a destructive/default-profile fallback.

When the same account reconnects, Weave retries that pending save automatically.

## 4. Public and private blob reads are Binder-safe chunks

Large blob reads no longer request an entire public profile/media blob in one AIDL `String transact()` response.

Both public and private reads now use **256 KiB binary chunks** (before Base64 expansion), validate non-empty/range-sized responses, and verify the final reconstructed byte length.

This keeps multi-megabyte objects away from single multi-megabyte Binder transactions and gives clearer failures if a daemon returns malformed ranges.

Uploads use the same 256 KiB chunk constant.

## 5. Persistent lifecycle / editor / daemon-RPC breadcrumbs

A second bounded diagnostic file, `weave-breadcrumbs.log`, is kept in Weave's private app storage.

It records metadata only — never request/response bodies, session tokens, profile text, authentication proofs, or media content.

Breadcrumbs include:

- Activity create/start/stop/destroy and memory-trim callbacks
- navigation destination changes
- Basic/Advanced editor entry, page number and a non-content document diagnostic id
- vault attach/detach and same-account reconnect detection
- deferred/recovered/failed profile persistence
- network reconnect reasons
- Binder service disconnection
- daemon client close reasons
- every failed daemon `transact()` with action name, request bytes, response bytes, elapsed time and exception
- successful daemon calls only when they are slow (>=250 ms) or large (>=256 KiB), to avoid diagnostics becoming a performance problem

Settings → Diagnostics → **Copy log** now includes this breadcrumb/RPC history before the existing network/group log.

## 6. Fatal Java/Kotlin crash breadcrumb

`MainActivity` installs a delegating uncaught-exception handler. Before Android's normal crash handler runs, Weave stores:

- thread name
- exception class/message
- a compact stack trace
- first cause, when present

The exception is then passed to Android's original handler; Weave does **not** swallow crashes.

This cannot capture every possible native/OS kill, but it should make intermittent Kotlin/Java crashes recoverable from Settings → Diagnostics even when logcat was not attached at the time.

## Version

- `versionCode = 27`
- `versionName = 0.9.2-reconnect-diagnostics`
