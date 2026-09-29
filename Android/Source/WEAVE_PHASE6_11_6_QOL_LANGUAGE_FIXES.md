# Weave Phase 6.11.6 — QoL + language fixes

This pass builds on Phase 6.11.5 and focuses on visible responsiveness, discovery thumbnails, and keeping the embedded VeilKnit UI and Weave UI consistent.

## Changes

### Claim-moderation feedback
- The Settings `Claim moderation of group` action disables while a claim is in progress.
- A small progress spinner and `Claiming…` label replace the normal button label while waiting.
- The in-group claim confirmation does the same thing while the moderation branch is being created.

### Group-search thumbnail prefetch
- Visible group-search rows whose cached directory entry has no thumbnail now request the selected/original branch header in the background.
- Refreshing the branch updates the existing GroupStore entry, including its thumbnail, without requiring the user to open the group first.
- Prefetch is tied to composed search rows so the app does not eagerly refresh every known group at once.

### Embedded VeilKnit App-display-name field
- The App display name field now explicitly uses the daemon dark-theme text, cursor, label, placeholder, border, and container colors.
- This fixes black text appearing on the dark edit field.

### One language preference for Weave + VeilKnit
- Weave and the embedded VeilKnit GUI now share `weave_veilknit_ui/language`.
- Changing language from Weave immediately updates the daemon UI.
- Changing language from the daemon UI updates Weave as well.
- Existing `veilknit_ui/language` preferences are migrated/maintained for compatibility.
- The selected language is also applied as the Android app locale so screens still using localized Android string resources follow the same setting.
- Simplified Chinese is normalized to `zh-CN` on both sides.

### Translation audit
- Converted remaining obvious hard-coded user-facing strings in Groups, content-filter UI, media UI, profile/editor UI, discovery diagnostics, and embedded daemon controls to the existing translation paths.
- Added French, Spanish, Russian, and Simplified Chinese entries for the newly routed strings.
- Added translations for the embedded daemon App display name / app-approval / QR controls that were previously falling back to English.

### Search layout
- The profile Search action is now a fixed 48 dp magnifying-glass icon button rather than the localized word `Search`.
- This prevents long labels such as French `Rechercher` from collapsing into a one-character-wide vertical button.

### Filtered thumbnails
- Compact `Filtered` and `Hidden` overlays use a smaller 9sp / 10sp line-height label and remain single-line.
- Content-filter status/action text now follows the selected UI language.

## Version
- `versionCode = 34`
- `versionName = 0.10.3-qol-language-fixes`

## Suggested device checks
1. Switch Weave to French, open the hidden VeilKnit GUI, and confirm it is already French.
2. Change VeilKnit to Spanish and return to Weave; confirm Weave changes to Spanish too.
3. In French Search, confirm the right-side action is a magnifying-glass icon and the row no longer produces a vertical `Rechercher` button.
4. Search Groups on a fresh/cache-light account and watch group thumbnails appear without first opening each group.
5. Claim moderation from Settings and from a group page; confirm the relevant action greys out and shows a spinner until completion.
6. Open a filtered profile/group thumbnail and confirm `Filtered` fits inside the compact overlay.
7. Open hidden VeilKnit controls and confirm `App display name` is readable on the dark field.
