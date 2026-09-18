# Weave local Content Filtering v2

This module is **viewer-side only**. It does not report a person, delete or reject a post, publish classifier scores, or change what another Weave user sees.

## Categories

Weave now exposes three independent local filters:

- **Sexual content** — image classification.
- **Gore / graphic content** — image classification.
- **Aggressive / hostile text** — comment/post text classification.

There is deliberately **no generic image "violence" filter**. Detecting violence broadly is much less well-defined than detecting gore/NSFL imagery, and Weave should not imply that it can reliably identify fights, weapons, threats, or violent context from a still image.

Each category defaults to **Off** and has its own sensitivity and action: Show / Warn / Blur / Hide.

## Image model

The sexual and gore filters share one compact local model:

`app/src/main/assets/content_filter/image_safety_xs.onnx`

Upstream: `OwenElliott/image-safety-classifier-xs` (MIT).

The model returns three probabilities in this order:

1. NSFL — gore / graphic imagery
2. NSFW — pornographic or highly suggestive imagery
3. SFW

The ONNX graph accepts a 224x224 RGB image. Upstream preprocessing and softmax are baked into the graph, so Weave resizes locally and sends RGB float values in the 0..255 range. Weave maps NSFL to the local Gore filter and NSFW to the local Sexual filter.

The ordinary FP32 ONNX export is the default for broad Android CPU compatibility. The model author also documents an FP16 export for GPU acceleration; that can be added later as an optional accelerated path after device/NNAPI testing.

## Text model

`app/src/main/assets/content_filter/toxic_minilm_int8.onnx`

Upstream: `minuva/MiniLMv2-toxic-jigsaw-onnx`.

Labels: toxic, severe_toxic, obscene, threat, insult, identity_hate. Weave maps these into its local aggression/threat score. This model is English-focused.

`app/src/main/assets/content_filter/vocab.txt` is its WordPiece vocabulary.

If the text model is missing, Weave retains only its conservative explicit-threat fallback.

## Local privacy/storage

- Preferences are stored inside the current VeilKnit account's encrypted `PrivateVault`.
- Model scores are cached only in memory.
- "Show anyway" decisions are local and memory-only.
- Nothing from the classifier is published to DHT/gossip, sent to the author, or treated as a moderation violation.

For migration from the first prototype, an old `violence_*` preference is read as the new Gore preference once, then future saves use `gore_*` keys.

## Installing models

Run one of these before compiling:

- Windows: `download_content_filter_models.bat`
- PowerShell: `download_content_filter_models.ps1`
- Linux/macOS: `./download_content_filter_models.sh`

The scripts intentionally fetch the model files from upstream rather than bundling them in this source ZIP. Once the app is compiled, the assets are packaged into the APK and the end user only needs the APK.

## Later work

- Profile-canvas media filtering.
- Video: sample keyframes and run the same NSFW/NSFL image classifier, then optionally combine with an on-device transcript/audio classifier.
- Per-person local exceptions such as "always show from this person."
