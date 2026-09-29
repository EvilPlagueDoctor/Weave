# Weave Phase 6.11.9 — Backup + section theme QoL

## Backup UI
- The embedded VeilKnit Backup page now gives all backup/recovery text fields explicit dark-theme field/text colours.
- Create local backup opens Android's normal Save As document picker.
- Rust still creates the encrypted backup using the existing account backup implementation; Weave writes it to an app-private temporary path, waits for the daemon success log, copies the encrypted archive to the selected document URI, then removes the temporary file.
- The local-backup button disables/greys out and shows a spinner while the export/copy is running.
- The operation has a 90-second UI timeout and leaves the daemon backup log available for diagnostics.

## Section colours
- Individual/profile mode uses the existing Weave red identity with a lightly red-tinted background/surfaces.
- Group mode and group destinations use a blue primary colour and lightly blue-tinted background/surfaces.
- Material buttons, selected tabs, links and other components that use MaterialTheme primary colours follow the active section automatically.
- Group destinations stay blue even when reached through another navigation path; Profile and Activity destinations stay red.

## Duplication/idempotency audit
Existing protection was retained:
- Groups-v2 canonical events are keyed by event ID. Re-delivery of the same signed body records additional transport evidence rather than inserting a second event. A same-ID/different-body event is kept as a conflict/equivocation observation instead of replacing the canonical event.
- Legacy/direct group intake has a bounded, persisted seen-event set, so direct/mailbox/service-request/reconciliation delivery is idempotent.
- Group pulses deduplicate conversations by conversation object ID, removed notices by post ID, and branch event reads by event ID.
- Profile comments are locally upserted by comment ID; pending notices are keyed by comment ID. Kept-comment reads collapse duplicate pointers/subkeys and validate pointer/author/digest before exposing content.
- Comment triage also notices same normalized body already present on a page and holds/provisionalizes it as anti-spam, but identical text submitted twice with two distinct IDs remains two intentional submissions.

Important distinction: the system suppresses network/retry duplicates of the same object/event. It does not globally treat identical text/content as the same post, because two intentional posts with the same words are allowed.

## Version
- versionCode 37
- versionName 0.10.6-backup-theme-qol
