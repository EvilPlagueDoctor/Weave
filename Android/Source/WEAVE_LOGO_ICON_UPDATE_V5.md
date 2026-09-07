# Weave Android logo / launcher icon update (v5)

The user-provided Weave logo is now installed as the Android application icon.

Changes:
- Added legacy launcher icons for mdpi, hdpi, xhdpi, xxhdpi, and xxxhdpi.
- Added Android 8.0+ adaptive launcher icon resources with a white background and the same Weave artwork centered inside the adaptive-icon safe area.
- Added `android:icon` and `android:roundIcon` to `AndroidManifest.xml`.
- Kept the uploaded artwork intact; generated density/adaptive resources are derived from that source image rather than a redesigned logo.

Android uses the application icon in the launcher, app settings, task/app switcher surfaces, and system splash behavior where applicable.
