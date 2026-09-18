# Weave Phase 6.10.2 — Startup/profile gate + Widget Studio load fix

This patch addresses two failures exposed after the full Chess widget was saved into an account/profile:

1. an established account could briefly/incorrectly land on **Create profile** during a daemon/vault reconnect race; and
2. a profile containing the ~122 KiB Chess widget, or opening Widget Studio with Chess in the private library, could perform source-package decode/compile work from the Android main thread and produce severe allocation churn / ANRs.

## Device evidence

The captured device run did not contain a Java `FATAL EXCEPTION`. Android instead reported a real input-dispatch ANR after repeated very large GC cycles (roughly 141–245 MiB reclaimed per cycle). During the ANR sample the Weave main thread consumed almost a full CPU core. The VeilKnit daemon was also heavily loaded, increasing contention, but Weave must remain responsive regardless.

## Profile / first-run gate

- `OnboardingState` is Compose-observable and no longer clears `completed` on a transient vault detach.
- A successfully loaded encrypted profile is authoritative evidence that setup already exists.
- First-run is not shown until the encrypted profile lookup and onboarding lookup have both reached a trustworthy result for a profile-less account.
- If onboarding says the account was already set up but the encrypted profile is genuinely absent, Weave shows a recovery/retry gate rather than silently creating/replacing a profile.
- A successful `persistActive()` marks the encrypted profile as present, so a newly created profile can advance without racing a separate flag.
- Same-account daemon reconnects preserve the in-memory document/editor state.

## Widget rendering on cold start

A profile containing a local widget previously resolved that widget synchronously from Canvas rendering. On a cold process, `WidgetRepository.find()` has no memory cache; decoding a source-only package recompiles/verifies the source. For Chess that could therefore happen on the Android main thread merely by drawing the profile.

Phase 6.10.2 changes this path:

- Canvas/rendering performs cache-only lookups.
- A missing local program schedules one bounded background load.
- encrypted-vault/package work happens away from the main thread;
- VWB verification happens on `Dispatchers.Default`;
- the page draws the inert widget placeholder while preparation is happening;
- completion updates the cache and redraws the page;
- diagnostics record `WIDGET_RENDER_PREPARE` / `WIDGET_RENDER_PREPARE_FAILED` with size/timing but no source contents.

The widget Inspector also uses cache-only package metadata and shows **Loading widget details…** while a large package is being prepared, instead of blocking Compose.

## Widget Studio

- Studio no longer enumerates/decodes the private widget library synchronously in its constructor.
- Entry shows a spinner and **Loading widget library…** / **Large widgets such as Chess can take a moment to prepare.**
- package loading and local-vault reads run off-main;
- Compile/Save and Compile Preview run asynchronously with an in-band busy overlay;
- opening the Widget Library loads entries asynchronously;
- a library-load failure falls back to a usable unsaved starter and records a diagnostic instead of throwing out of the Compose coroutine;
- Visual-tab edits no longer recompile the entire 122 KiB Chess source on every slider tick/keystroke. Explicit Compile/Save remains the parser + verifier gate.

## Diagnostic-log allocation fix

`SocialNetworkController.log()` used to copy a growing ~180 KiB debug-log String into `SocialUiState` on every gossip/network log line. Because the root app collects that state, a busy startup could cause repeated whole-root recomposition signals and tens/hundreds of MiB of temporary String allocation.

The persisted log file remains authoritative. The UI-facing debug tail is now refreshed at most once per second. Normal log lines still go to disk immediately.

## Security model

No widget trust boundary is weakened:

- local/private widget storage remains source-first/source-only for persistence;
- published widgets remain source-only;
- source received from another user is still compiled locally;
- locally created bytecode is still verified before execution;
- background preparation merely moves expensive trusted-client work off the Android UI thread.

