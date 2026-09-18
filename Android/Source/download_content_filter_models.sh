#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TARGET="$ROOT/app/src/main/assets/content_filter"
mkdir -p "$TARGET"

download() {
  name="$1"
  url="$2"
  echo "Downloading $name ..."
  curl -fL --retry 3 --connect-timeout 20 "$url" -o "$TARGET/$name.download"
  mv -f "$TARGET/$name.download" "$TARGET/$name"
}

download image_safety_xs.onnx 'https://huggingface.co/OwenElliott/image-safety-classifier-xs/resolve/main/onnx/image-safety-classifier-xs.onnx?download=true'
download toxic_minilm_int8.onnx 'https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/main/model_optimized_quantized.onnx?download=true'
download vocab.txt 'https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/main/vocab.txt?download=true'

if command -v sha256sum >/dev/null 2>&1; then
  expected='bcd9dfb48cad802ac8f7cd789e1294f1f0b22d532797bd41f5a11694e3c269a0'
  actual=$(sha256sum "$TARGET/toxic_minilm_int8.onnx" | awk '{print $1}')
  if [ "$actual" != "$expected" ]; then
    echo "SHA-256 mismatch for toxic_minilm_int8.onnx" >&2
    rm -f "$TARGET/toxic_minilm_int8.onnx"
    exit 1
  fi
fi

echo 'Content-filter models installed. You can now build Weave normally.'
