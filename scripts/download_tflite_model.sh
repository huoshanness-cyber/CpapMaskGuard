#!/usr/bin/env bash
# Download on-device MobileNet V3 Small image embedder into app assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/mobilenet_v3_small_embedder.tflite"
URL="https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
mkdir -p "$(dirname "$DEST")"
curl -L --fail -o "$DEST" "$URL"
echo "Saved $(wc -c <"$DEST") bytes to $DEST"
