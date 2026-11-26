#!/usr/bin/env bash
# Regenerates the native avatar's data from the WebView avatar, so the two renderers cannot drift:
#   export.sh model     -> avatar/src/main/resources/dev/hect/lauravoice/avatar/laura-model.json (+ watch LOD flags)
#   export.sh golden    -> avatar/src/test/resources/golden/*.json (instance buffers the JVM parity test compares against)
#   export.sh refs      -> docs/avatar/native/web/*.png (WebView stills for the side-by-side comparisons)
# Run it after any change to clients/android/app/src/main/assets/avatar/index.html.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)
IMG=mcr.microsoft.com/playwright:v1.65.1-noble
RES=$ROOT/clients/android/avatar/src/main/resources/dev/hect/lauravoice/avatar
TMP=$(mktemp -d /tmp/laura-export.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

run() { # <out> <xmode> [cases] [bg]
  docker run --rm --network none -u "$(id -u):$(id -g)" -v "$ROOT:$ROOT:ro" -v "$1:$1" -w "$HERE" "$IMG" \
    node "$HERE/export.mjs" "$ROOT" "$1" "${@:2}"
}

case ${1:-model} in
  model)
    run "$TMP" model
    # watch LOD: the most pixels each item covers in any frame of any state or reaction, at the watch's 480 px
    run "$TMP" lod '[{"name":"lod","px":480,"theme":"dark","clearHex":"#000000"}]' 000000
    mkdir -p "$RES"
    python3 "$HERE/lod.py" "$TMP/laura-model.json" "$TMP/lod.json" "$RES/laura-model.json"
    ;;
  golden)
    G=$ROOT/clients/android/avatar/src/test/resources/golden; mkdir -p "$G"
    run "$G" golden "$(python3 "$HERE/cases.py" golden dark)"
    run "$G" golden "$(python3 "$HERE/cases.py" golden clear)" transparent
    ;;
  refs)
    D=$ROOT/docs/avatar/native/web; mkdir -p "$D"
    run "$D" refs "$(python3 "$HERE/cases.py" refs dark)"
    run "$D" refs "$(python3 "$HERE/cases.py" refs watch)" 000000
    run "$D" refs "$(python3 "$HERE/cases.py" refs clear)" transparent
    ;;
  *) echo "usage: $0 model|golden|refs" >&2; exit 2 ;;
esac
