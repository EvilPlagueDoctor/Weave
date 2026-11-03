# Weave 0.12.0-dev4: Profile text accessibility

Settings → Accessibility → Profile text size. The slider offers 100% (the existing compact default) through 300% in 25% increments. The setting previews the size and includes Reset to default.

- Applies to text authored within profile pages, including the selectable published-page viewer and the own-profile published preview. It updates without restarting the application.
- Does **not** resize app navigation labels, group-post text, or the profile editor's authored design. Android's normal accessibility/system font settings continue to cover app UI.
- Persists in device-local Android SharedPreferences (`weave_accessibility`), not in signed profiles or the encrypted daemon/account vault. Never transmitted to peers.
- All existing profiles render **identically to dev3 at 100%**, retaining `PROFILE_LEGACY_TEXT_SCALE = 0.35f`. Only the viewer's Compose text receives the additional multiplier. Body text line-height scales with it.
- Very high magnification can cause text to wrap into or overlap adjacent authored elements; no profile layout is rewritten. Long-press selection and page zoom remain available.
- versionName `0.12.0-dev4`, versionCode `48`. Build version is already displayed in Settings.

## Suggested device validation
1. Verify Settings reads `Weave 0.12.0-dev4 (code 48)`.
2. At 100%, compare Hishiryo and Anne Käfer to dev3; sizes should remain identical.
3. Change to 150% and 200%, open the same profiles, and verify the text grows while images and saved design positions do not change.
4. Verify long-press copy/selection still works, and reopening Weave preserves the chosen percentage.
5. Reset to 100%; verify original compact typography returns, including after switching accounts.

No daemon, mailbox, gossip, or published profile schemas were changed. Full Android build/device screenshot verification was not possible in the preparation environment.
