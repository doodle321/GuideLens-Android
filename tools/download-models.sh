#!/usr/bin/env bash
# Downloads GuideLens ML models with verification (TFL3 magic + size).
# After the first download, pin the printed SHA-256 values in app/models.sha256
# to make builds enforce them.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets"
mkdir -p .

valid() { # $1 = file
  [ -f "$1" ] && [ "$(dd if="$1" bs=1 skip=4 count=4 2>/dev/null)" = "TFL3" ]
}

fetch() { # $1 = filename, $2 = min bytes, $3... = urls
  local f="$1"; local min="$2"; shift 2
  if valid "$f" && [ "$(wc -c < "$f")" -gt "$min" ]; then
    echo "OK: $f already present"; sha256sum "$f"; return 0
  fi
  for url in "$@"; do
    echo "Trying: $url"
    if curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$f.part" "$url" \
       && valid "$f.part" && [ "$(wc -c < "$f.part")" -gt "$min" ]; then
      mv "$f.part" "$f"
      echo "Downloaded $f ($(wc -c < "$f") bytes)"
      echo "SHA-256: $(sha256sum "$f" | cut -d' ' -f1)  <- add this line to app/models.sha256"
      return 0
    fi
    rm -f "$f.part"
  done
  return 1
}

fetch detect.tflite 1000000 \
  "https://cdn.jsdelivr.net/gh/am15h/object_detection_flutter@master/assets/detect.tflite" \
  "https://raw.githubusercontent.com/am15h/object_detection_flutter/master/assets/detect.tflite" \
  || { echo "::error::could not obtain detect.tflite"; exit 1; }

fetch deeplab_cityscapes.tflite 1000000 \
  "https://raw.githubusercontent.com/google-coral/edgetpu/master/test_data/deeplabv3_mnv2_cityscapes_quant.tflite" \
  || echo "WARNING: segmentation model unavailable (optional)"
