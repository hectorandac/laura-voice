#!/bin/sh
# render.sh <html> <png> <width> <height>: screenshot an HTML sheet with the playwright image's headless chromium
set -e
h=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
o=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
docker run --rm --network none -u "$(id -u):$(id -g)" -v "$(dirname "$h"):$(dirname "$h")" -v "$(dirname "$o"):$(dirname "$o")" \
  mcr.microsoft.com/playwright:v1.65.1-noble /ms-playwright/chromium_headless_shell-1255/chrome-headless-shell-linux64/chrome-headless-shell \
  --no-sandbox --hide-scrollbars --force-device-scale-factor=1 --window-size="$3,$4" --screenshot="$o" "file://$h" 2>/dev/null
