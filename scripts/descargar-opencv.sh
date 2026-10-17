#!/usr/bin/env bash
# Descarga el SDK de OpenCV para Android junto a esta carpeta (../opencv-android-sdk),
# que es donde lo busca app/src/main/cpp/CMakeLists.txt si no se define OPENCV_ANDROID_SDK.
set -euo pipefail
VERSION=${1:-4.10.0}
destino="$(cd "$(dirname "$0")/../.." && pwd)/opencv-android-sdk"
if [ -d "$destino/sdk" ]; then echo "Ya está en $destino"; exit 0; fi
tmp=$(mktemp -d)
curl -L -o "$tmp/opencv.zip" "https://github.com/opencv/opencv/releases/download/$VERSION/opencv-$VERSION-android-sdk.zip"
unzip -q "$tmp/opencv.zip" -d "$tmp"
mv "$tmp/OpenCV-android-sdk" "$destino"
rm -rf "$tmp"
echo "OpenCV $VERSION en $destino"
