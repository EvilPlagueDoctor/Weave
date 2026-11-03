# Selectable profile text — legacy sizing compatibility

This is a **Kotlin/Compose-only** compatibility fix on top of Weave Phase 2.

- Previously the Android StaticLayout canvas used `TextPaint.textSize = element.fontSize * density`, treating the author's saved size as density-independent units.
- The selectable-text migration used `fontSize.sp`, which incorporates accessibility font scaling and may make authored pages substantially larger than they were designed.
- The editor's TextMeasurer, the inline text editor, and the selectable published viewer now use `fontSize.dp.toSp()` with their active density. This preserves the old physical font size while retaining Compose selection/copy support.
- The style also sets an explicit text line height (~1.17x font size), avoiding Material's default body text line height when an author saved a smaller font. Exact glyph wrapping may still differ slightly from Android StaticLayout; device screenshot comparison is required.
- We do not alter the saved profile JSON, stored font sizes, text positions, editor controls, or the Rust daemon.
- Bumped development build to `versionCode=46`, `versionName=0.12.0-dev2`. Do not publish this test code as the final official release version without coordinating version codes.

## Test

On the same phone, compare two existing public profiles with the original small text:
- Hishiryo: title and biography should be compact, with intentional empty space intact.
- Anne Käfer: the bio should fit as in the earlier screenshot, with its remaining lines visible.

Long-press the profile text to verify selection and copy still work. Also open your own editor and confirm its preview roughly matches its published view, including while resizing font. The full Android APK was **not** built in this environment. Build the unsigned development APK via GitHub Actions and sign locally with the same original release keystore.
