#!/usr/bin/env bash
# Renders the widget design stills: render.sh [board ...]  (default: all) -> docs/app/widget-design/<board>.png
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../../.." && pwd); OUT=$(dirname "$HERE")
ALL="01-home-dark-4x2:360x800@3 02-home-light-4x3:360x800@3 03-home-small-sizes:360x800@3 04-lock-private:360x800@3 07-configure:360x800@3 05-states:1480x1250@2 06-sizes-faces:1240x1580@2"
if [ $# -gt 0 ]; then ALL=$(for b in "$@"; do for e in $ALL; do [[ $e == $b:* ]] && echo "$e" || true; done; done); fi
CMDS=""
for e in $ALL; do
  b=${e%%:*}; g=${e#*:}; wh=${g%@*}; s=${g#*@}
  CMDS+="\$CH --no-sandbox --hide-scrollbars --allow-file-access-from-files --virtual-time-budget=8000 --window-size=${wh/x/,} --force-device-scale-factor=$s --screenshot=/out/$b.png 'file:///repo/docs/app/widget-design/src/widgets.html?b=$b' 2>/dev/null >/dev/null; echo $b;"
done
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/repo:ro" -v "$OUT:/out" mcr.microsoft.com/playwright:v1.65.1-noble \
  sh -c "CH=\$(ls /ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell); HOME=/tmp; $CMDS"
