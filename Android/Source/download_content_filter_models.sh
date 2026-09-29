#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TARGET="$ROOT/app/src/main/assets/content_filter"
mkdir -p "$TARGET"

IMAGE_REV='606ad3dfd6a023215e3ab0797040437cc365977b'
TOXIC_REV='edeaa44a3eed98842d1139619bfb5dc55fafcfad'

download() {
  name="$1"
  url="$2"
  expected_sha256="${3:-}"
  echo "Downloading $name ..."
  curl -fL --retry 3 --connect-timeout 20 "$url" -o "$TARGET/$name.download"

  if [ -n "$expected_sha256" ]; then
    if ! command -v sha256sum >/dev/null 2>&1; then
      echo "sha256sum is required to verify $name" >&2
      rm -f "$TARGET/$name.download"
      exit 1
    fi
    actual=$(sha256sum "$TARGET/$name.download" | awk '{print $1}')
    if [ "$actual" != "$expected_sha256" ]; then
      echo "SHA-256 mismatch for $name" >&2
      echo "  expected: $expected_sha256" >&2
      echo "  actual:   $actual" >&2
      rm -f "$TARGET/$name.download"
      exit 1
    fi
  fi

  mv -f "$TARGET/$name.download" "$TARGET/$name"
}

download image_safety_xs.onnx \
  "https://huggingface.co/OwenElliott/image-safety-classifier-xs/resolve/$IMAGE_REV/onnx/image-safety-classifier-xs.onnx?download=true" \
  '8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689'

download toxic_minilm_int8.onnx \
  "https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/$TOXIC_REV/model_optimized_quantized.onnx?download=true" \
  'bcd9dfb48cad802ac8f7cd789e1294f1f0b22d532797bd41f5a11694e3c269a0'

# This file is small and comes from the same immutable upstream revision as the
# toxicity model. Add an independent hash here once it has been recorded from a
# clean download; the immutable revision already prevents silent upstream drift.
download vocab.txt \
  "https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/$TOXIC_REV/vocab.txt?download=true"

echo 'Content-filter models installed and pinned to immutable upstream revisions.'
