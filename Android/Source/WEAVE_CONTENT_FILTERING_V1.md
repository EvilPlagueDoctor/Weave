# Weave local Content Filtering v1

Superseded by `WEAVE_CONTENT_FILTERING_V2.md`.

V2 replaces the old NSFW-only MobileNet path and the placeholder Graphic Violence slot with one compact `OwenElliott/image-safety-classifier-xs` ONNX classifier that separately supplies NSFW and NSFL(gore) scores. Generic still-image "violence" filtering was intentionally removed.
