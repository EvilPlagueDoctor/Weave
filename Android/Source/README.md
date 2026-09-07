# Android Kotlin build

Jetpack Compose implementation.

Build debug APK:

```
build_project.bat
```

or

```
./build_project.sh
```

Clean with `clean_project.bat` / `clean_project.sh`.

The app stores local profile drafts under its private `files/profiles` directory. `live_profile.txt` is only a local stand-in for a future published profile DHT.

## Startup crash diagnostic helper

If a device still exits immediately at launch, connect it with USB debugging enabled and run:

```
capture_startup_log.bat
```

This relaunches the package and writes `startup_logcat.txt` beside the script so the exact exception can be shared without manually filtering Logcat.

## Hotfix 4 editor notes

The Android editor now uses Background / Boxes / Foreground workspaces, animated box entry/exit, collapsible property groups, depth drag-reordering and precise numeric entry. All application/editor labels are Android string resources in `app/src/main/res/values/strings.xml`; later translations can use standard `values-<language>/strings.xml` folders without changing VSPF data.

## UI revamp (version 5)

This pass focuses on editor clarity rather than profile-format changes:

- The bottom edit/tool strip now spans the full window so the inspector cannot cover it.
- Toolbar actions use clearer glyph + text pairs (Select, Text, Box, Link, Button, Stamp, Media, Widget, Page).
- Background / Boxes / Contents modes are explicitly labelled instead of relying on terse abbreviations.
- The old flat "Page / box / item" selector is now a collapsible Pages & layers tree; each page is visually separated and its contents are indented beneath it.
- The inspector uses card-like collapsible sections and a clearer selected-item header.
- Save and Save As are full-width actions so narrow inspector widths do not truncate both into ambiguous labels.
- The canvas uses a subtle dotted workspace, elevated page surface and a dedicated page/mode header.
- The editor uses a light indigo/teal palette with stronger selected-state contrast.

The profile document codec/model is unchanged by this UI pass.

## Revamped 4 text editing

- In **Contents / Inside box** mode, double-tap or press-and-hold a text item to edit it directly on the page.
- The Inspector collapse control stays pinned at the top while the Inspector body scrolls.
- Text and button label fields use buffered editing so Compose recomposition cannot overwrite an in-progress edit.
- Text appearance includes Default font, size, bold, italic, underline, colour and alignment.
- Buttons include solid/fade background, border, text styling and a placeholder Button Image command.

## WidgetLab 6

The Android build now includes Widget Studio. Open it from the page designer header. Widget packages are stored locally under the app's private `widgets/` directory as `*.widget.txt` files using a Base64 text envelope around serialized binary package data. Profile placement uses `local-widget:<id>` as the temporary local stand-in for the future network record reference.

`TODO(network)`: replace local widget lookup, link/fork origin lookup, and host-stream mocks with DHT/VeilKnit-backed implementations. Do not expose raw network APIs to VeilWidget code when this happens.
