#!/usr/bin/env bash
# Renders the native avatar off-screen in the Android emulator (laura-android-emu image, swiftshader GLES 3) with
# AvatarSnapshot, from the wear debug APK (no install: app_process on the APK), and pulls the PNGs.
#   emu.sh shots   -> docs/avatar/native/native/*.png   (the cases of tools/avatar-export/cases.py, as the WebView refs)
#   emu.sh perf    -> docs/avatar/native/perf-emulator.txt
# Build first: ./gradlew :wear:assembleDebug
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
APK=$ROOT/clients/wear/build/outputs/apk/debug/wear-debug.apk
MODE=${1:-shots}
WORK=$(mktemp -d /tmp/laura-emu.XXXXXX); trap 'rm -rf "$WORK"' EXIT
cp "$APK" "$WORK/a.apk"
python3 "$ROOT/clients/android/tools/avatar-export/cases.py" native all > "$WORK/cases.json"
cat > "$WORK/run.sh" <<'EOF'
set -e
export ANDROID_AVD_HOME=/tmp/avd ANDROID_EMULATOR_HOME=/tmp/emu HOME=/tmp
mkdir -p $ANDROID_AVD_HOME $ANDROID_EMULATOR_HOME
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager -s create avd -n laura -k "system-images;android-35;google_apis;x86_64" -d pixel_6 >/dev/null
$ANDROID_HOME/emulator/emulator -avd laura -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on >/tmp/emu.log 2>&1 &
ADB=$ANDROID_HOME/platform-tools/adb
$ADB wait-for-device
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
$ADB push /w/a.apk /data/local/tmp/a.apk >/dev/null
$ADB push /w/cases.json /data/local/tmp/cases.json >/dev/null
RUN="CLASSPATH=/data/local/tmp/a.apk app_process / dev.hect.lauravoice.avatar.AvatarSnapshot"
if [ "$MODE" = perf ]; then
  $ADB shell "$RUN perf /data/local/tmp/perf.txt"
  $ADB pull /data/local/tmp/perf.txt /w/perf.txt >/dev/null
  $ADB shell "cat /proc/cpuinfo | grep -m1 'model name'; getprop ro.hardware.egl; dumpsys SurfaceFlinger | grep -m1 GLES" >> /w/perf.txt || true
else
  $ADB shell "$RUN shots /data/local/tmp/cases.json /data/local/tmp/shots"
  $ADB pull /data/local/tmp/shots /w/shots >/dev/null
fi
$ADB emu kill >/dev/null 2>&1 || true
chown -R "$UID_GID" /w
EOF
docker run --rm --name "laura-native-avatar-emu-$$" --device /dev/kvm -e MODE="$MODE" -e UID_GID="$(id -u):$(id -g)" -v "$WORK:/w" laura-android-emu bash /w/run.sh
if [ "$MODE" = perf ]; then cp "$WORK/perf.txt" "$HERE/perf-emulator.txt"; cat "$HERE/perf-emulator.txt"
else mkdir -p "$HERE/native"; cp "$WORK"/shots/*.png "$HERE/native/"; ls "$HERE/native" | wc -l; fi
chown -R "$(id -u):$(id -g)" "$HERE" 2>/dev/null || true
