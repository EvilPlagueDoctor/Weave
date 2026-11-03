# Weave 0.12.0-dev3: compact profile typography and build ID

- Recovered original published-profile proportions from the supplied side-by-side screenshots.
  Title widths were roughly 0.35 of the selectable version after normalizing by screen width.
- Apply a 0.35 compatibility scale *only* to authored profile text, including editor and published viewer, via `AuthoredPageTextStyle.kt`. This is a first calibration, not pixel-perfect matching against the old renderer.
- Preserve individual text-element selection and clipboard copy. No profile data, DHT, daemon, or moderation changes.
- Settings shows the actual APK `versionName` and long `versionCode` from PackageManager (not a hardcoded string). For this build expect `0.12.0-dev3 (code 47)`.
- The original version size patch (`dev2`) converted dp/sp but at default Android font scaling that is often a no-op; this update intentionally applies a visible scale change.

## On-device checks
1. Open Settings before checking profiles; confirm `Weave 0.12.0-dev3 (code 47)`.
2. Compare Hishiryo and Anne Käfer to their compact **Original** screenshots; titles and body should be substantially smaller, preserve whitespace, and remain selectable.
3. Verify the profile editor text sizes and published preview remain consistent, including manual text editing, line wraps, and long-press copy.
4. If necessary, tune only `PROFILE_LEGACY_TEXT_SCALE` after seeing new screenshots, not individual users' saved profiles.

## Build/sign
Build the unsigned release with `-PweaveFdroidBuild=true`, sign locally with the matching original release key, and verify the certificate. `versionCode=47` should update prior code 46, provided signing certificate and package ID match.

Not validated in a complete Android build here.
