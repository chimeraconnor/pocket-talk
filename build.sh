#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Building Pocket Talk APK ==="

docker build -t pocket-talk-builder . 2>&1 | tail -20

mkdir -p output
docker run --rm -v "$(pwd)/output:/output" pocket-talk-builder

echo "=== APK ready: output/pocket-talk.apk ==="
ls -lh output/pocket-talk.apk
