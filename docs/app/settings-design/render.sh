#!/usr/bin/env bash
# Stills of the settings design: mock.html -> *.png at 1080x2400 (headless Chromium in the Playwright image).
# FONTS: a directory with Roboto and Noto Sans KR TTFs (the phone's faces), mounted as system fonts.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
FONTS=${FONTS:?set FONTS to a directory with Roboto and NotoSansKR ttf files}
docker run --rm --init -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$ROOT:$ROOT" -v "$FONTS:/usr/share/fonts/truetype/laura:ro" -w "$HERE" \
  mcr.microsoft.com/playwright:v1.65.1-noble sh -c 'cd /tmp && npm init -y >/dev/null && npm i --silent playwright@1.65.1 >/dev/null && cp '"$HERE"'/render.mjs /tmp/ && sed -i "s|path.dirname(fileURLToPath(import.meta.url))|\"'"$HERE"'\"|" /tmp/render.mjs && node /tmp/render.mjs'
