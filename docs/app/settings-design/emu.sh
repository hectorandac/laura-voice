#!/usr/bin/env bash
# Screenshots of the implemented settings on the emulator (laura-android-emu, Pixel 6, 1080x2400) into emu/.
# The debug APK only carries arm64 native code, so a copy gets the x86_64 build of the one library it has
# (androidx graphics-path, from the Gradle cache) and is re-signed with the debug key. Build first.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
WORK=$(mktemp -d /tmp/laura-settings-emu.XXXXXX); trap 'rm -rf "$WORK"' EXIT
cp "$ROOT/clients/android/app/build/outputs/apk/debug/app-debug.apk" "$WORK/in.apk"
SO=$(find "$HOME/.cache/laura-gradle/caches" -path '*graphics-path*/jni/x86_64/libandroidx.graphics.path.so' | head -1)
python3 - "$WORK/in.apk" "$SO" "$WORK/tmp.apk" <<'PY'
import shutil, sys, zipfile
shutil.copy(sys.argv[1], sys.argv[3])
with zipfile.ZipFile(sys.argv[3], "a") as z:
    z.write(sys.argv[2], "lib/x86_64/libandroidx.graphics.path.so", compress_type=zipfile.ZIP_STORED)
PY
docker run --rm -v "$WORK:/w" -v "$HOME/.android:/android" laura-android-build sh -c '
  set -e; cd /w; BT=$(ls -d $ANDROID_HOME/build-tools/* | tail -1)
  $BT/zipalign -f -p 4 tmp.apk a.apk
  $BT/apksigner sign --ks /android/debug.keystore --ks-pass pass:android --key-pass pass:android a.apk
  chmod 666 a.apk'
cat > "$WORK/run.sh" <<'IN'
set -e
export ANDROID_AVD_HOME=/tmp/avd ANDROID_EMULATOR_HOME=/tmp/emu HOME=/tmp
mkdir -p $ANDROID_AVD_HOME $ANDROID_EMULATOR_HOME /w/emu
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager -s create avd -n laura -k "system-images;android-35;google_apis;x86_64" -d pixel_6 >/dev/null
$ANDROID_HOME/emulator/emulator -avd laura -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on >/tmp/emu.log 2>&1 &
A=$ANDROID_HOME/platform-tools/adb
$A wait-for-device
until [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
$A install -r /w/a.apk >/dev/null
$A shell settings put global window_animation_scale 0; $A shell settings put global transition_animation_scale 0
$A shell pm grant dev.hect.lauravoice android.permission.RECORD_AUDIO
$A shell pm grant dev.hect.lauravoice android.permission.POST_NOTIFICATIONS || true
$A shell cmd statusbar collapse >/dev/null 2>&1 || true
PKG=dev.hect.lauravoice
shot() { sleep ${2:-2}; $A exec-out screencap -p > /w/emu/$1.png; echo $1; }
night() { $A shell cmd uimode night $1 >/dev/null; sleep 1; }
tap() {
  $A shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  b=$($A shell cat /sdcard/u.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  set -- $b; $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1.2
}
back() { $A shell input keyevent 4; sleep 1; }
settings() { $A shell am start -a dev.hect.lauravoice.SETTINGS -n $PKG/.MainActivity >/dev/null; }

# first run: not set up, settings opens by itself
night yes; $A shell am start -n $PKG/.MainActivity >/dev/null; shot welcome-dark 4
$A shell am force-stop $PKG; night no; $A shell am start -n $PKG/.MainActivity >/dev/null; shot welcome-light 4
$A shell am force-stop $PKG
# set up (an unreachable gateway: Offline), developer options unlocked
$A shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/laura.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="prefs_version" value="1" />
    <string name="url">wss://10.0.2.2:8443/ws</string>
    <string name="key">example-access-key</string>
    <string name="pin">9f2c41d7a0e3b85c6f1e2d4a7b9c0e13f5a8d2c64b7e910a3f5c8d1e2b4a6c70</string>
    <string name="mode">handsfree</string>
    <string name="lang">auto</string>
    <string name="car_mode">auto</string>
    <boolean name="developer" value="true" />
</map>
XML
night yes; $A shell am start -n $PKG/.MainActivity >/dev/null; shot main-coach 14
tap "Got it"; shot main 1
$A shell input tap 996 2230; shot home-dark 2
tap "Laura"; shot server-dark; back
tap "Your voice"; shot voice-dark; back
tap "Car"; shot car-dark; back
tap "Watch"; shot watch-dark; back
tap "Call her from anywhere"; shot invoke-dark; back
$A shell input swipe 540 1800 540 500 300; sleep 1; tap "About"; shot about-dark; back
$A shell input swipe 540 1400 540 600 300; shot home-dark-bottom 1
$A shell am force-stop $PKG; night no; settings; shot home-light 4
tap "Laura"; shot server-light; back
tap "Car"; shot car-light; back
tap "Call her from anywhere"; shot invoke-light; back
$A shell input swipe 540 1800 540 500 300; sleep 1; tap "About"; shot about-light; back
$A emu kill >/dev/null 2>&1 || true
chown -R "$UID_GID" /w/emu
IN
docker run --rm --name "laura-settings-emu-$$" --device /dev/kvm -e UID_GID="$(id -u):$(id -g)" -v "$WORK:/w" laura-android-emu bash /w/run.sh
mkdir -p "$HERE/emu"; cp "$WORK"/emu/*.png "$HERE/emu/"; ls "$HERE/emu"
