#!/bin/sh
# Build the three images and push them: REGISTRY=registry.example.com/laura-voice deploy/build.sh [tag] [image...]
set -eu
: "${REGISTRY:?set REGISTRY, e.g. registry.example.com/laura-voice}"
TAG="${1:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}"
[ $# -gt 0 ] && shift
IMAGES="${*:-llama-server tts gateway}"
cd "$(dirname "$0")/.."
for img in $IMAGES; do
  docker build -f "deploy/docker/$img.Dockerfile" -t "$REGISTRY/$img:$TAG" .
  docker push "$REGISTRY/$img:$TAG"
done
