# Weave Content Filter Diagnostics v3

This pass adds persistent and Logcat diagnostics around local viewer-side content filtering.
No image bytes, post text, authentication data, or classifier inputs are written to the log.

## Main finding in this source package

`app/src/main/assets/content_filter/image_safety_xs.onnx` is not present in the source ZIP.
Only `README.txt` is present in that directory. If the app is built in that state, Sexual/Gore
classification cannot run and the classifier returns zero scores. Run:

`download_content_filter_models.bat`

before building on Windows. The build BAT files now print an explicit warning when the image model
is absent.

## New diagnostics

Filter events are written both to Logcat under tag `WeaveContentFilter` and to the existing copied
Weave diagnostic breadcrumbs under category `CONTENT_FILTER`.

Useful lines include:

- `INIT ...` — reports whether the image/text model assets exist.
- `VAULT_ATTACHED ...` — reports the loaded sensitivity/action settings and model readiness.
- `PREFERENCE ...` — reports changes to a category, including the numeric threshold.
- `IMAGE_CLASSIFY_BEGIN ...` — image dimensions, settings, threshold, and model readiness.
- `IMAGE_MODEL_LOAD_OK ...` / `IMAGE_MODEL_LOAD_FAILED ...` — ONNX session load result.
- `IMAGE_MODEL_RAW nsfl=... nsfw=... sfw=...` — raw classifier probabilities.
- `IMAGE_CLASSIFY_RESULT ... decision=...` — mapped score and resulting Show/Warn/Blur/Hide decision.
- `IMAGE_RENDER ...` — the action actually handed to the Compose filter gate.
- `REVEAL ...` — records a local "show anyway" action for that content ID.

## UI fix

The selected image preview inside **Create a post** previously rendered through a raw Compose
`Image(...)`, bypassing `FilteredImage(...)`. It now uses the same content-filter gate as group post
thumbnails and full images.

## Missing-model visibility

Sexual/Gore settings now display an error-colored message:

`Image model NOT installed — images cannot be classified`

when the ONNX asset is absent, rather than the less prominent old status text.
