# Weave Phase 6.10.5 — Copy Log compile fix

Fixes the Kotlin compile errors introduced by the Phase 6.10.4 Copy Log crash protection.

## Cause
`tr(...)` is a `@Composable` translation helper. Phase 6.10.4 called it from the non-composable `onClick` callback for the Diagnostics / Copy log button.

## Fix
The translated status strings are now resolved in composable scope before the button callback:
- `Copied`
- `Copied recent log (trimmed)`
- `Copy failed`

The click handler only assigns those already-resolved strings. The clipboard-size protection and fallback behavior from 6.10.4 remain unchanged.

## Content-filter models
The current loader expects the three optional classifier assets under:
`app/src/main/assets/content_filter/`

Expected filenames:
- `image_safety_xs.onnx`
- `toxic_minilm_int8.onnx`
- `vocab.txt`
