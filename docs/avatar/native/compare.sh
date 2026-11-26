#!/usr/bin/env bash
# WebView refs (web/, from tools/avatar-export/export.sh refs) vs native emulator frames (native/, from emu.sh shots)
# -> compare/<case>.png (WebView | native | diff), compare/sheet.png, compare/stats.json
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
docker run --rm --network none -u "$(id -u):$(id -g)" -v "$HERE:$HERE" -w "$HERE" mcr.microsoft.com/playwright:v1.65.1-noble \
  node "$HERE/compare.mjs" "$HERE/web" "$HERE/native" "$HERE/compare"
cat "$HERE/compare/stats.json"
