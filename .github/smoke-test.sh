#!/bin/bash
# Emulator smoke test for CI: install, open every tab, play a video, an AVI and an MP3,
# run a short monkey session, and fail on any crash or ANR in the app.
set -u
PKG=com.hafij.astraplayer
OUT=smoke
mkdir -p "$OUT"
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
alive() { adb shell pidof "$PKG" > /dev/null; }

adb install -r apks/app-x86_64-debug.apk || exit 1
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell mkdir -p /sdcard/Movies/Smoke /sdcard/Music/Smoke
adb push "media/Smoke video.mp4" "media/Smoke video.srt" "media/Old clip.avi" /sdcard/Movies/Smoke/
adb push "media/Smoke song.mp3" /sdcard/Music/Smoke/
adb logcat -c

# Library screen in light and dark.
adb shell am start -W -n "$PKG/.MainActivity"
sleep 12
shot 01-files
adb shell cmd uimode night yes; sleep 4; shot 02-files-dark
adb shell cmd uimode night no; sleep 4

# Player: video with external subtitle (first run also builds the font cache).
adb shell am start -W -a android.intent.action.VIEW -t video/mp4 \
  -d "file:///sdcard/Movies/Smoke/Smoke%20video.mp4" -n "$PKG/.PlayerActivity"
sleep 45
shot 03-player-video
# Swipe-seek: hold a sideways drag on the video to bring up the seek preview thumbnail.
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input motionevent DOWN $((W/4)) $((H/2))
for step in 1 2 3 4 5 6; do adb shell input motionevent MOVE $((W/4 + step*W/12)) $((H/2)); sleep 0.3; done
sleep 2
shot 03b-seek-preview
adb shell input motionevent UP $((W*3/4)) $((H/2))
sleep 3
adb shell am start -W -a android.intent.action.VIEW -t video/avi \
  -d "file:///sdcard/Movies/Smoke/Old%20clip.avi" -n "$PKG/.PlayerActivity"
sleep 10
shot 04-player-avi
adb shell am start -W -a android.intent.action.VIEW -t audio/mpeg \
  -d "file:///sdcard/Music/Smoke/Smoke%20song.mp3" -n "$PKG/.PlayerActivity"
sleep 8
shot 05-player-mp3
adb shell input keyevent KEYCODE_BACK
sleep 3

# Random taps and swipes inside the app only (no system keys).
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --throttle 250 -s 42 -v 600 > "$OUT/monkey.txt" 2>&1 || true
sleep 3
adb shell am start -W -n "$PKG/.MainActivity"
sleep 5
shot 06-after-monkey

adb logcat -d > "$OUT/logcat.txt"
FAIL=0
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | grep -q .; then
  echo "::error::Crash or ANR found"
  grep -A20 -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -80
  FAIL=1
fi
if grep -q "CRASH: $PKG\|NOT RESPONDING: $PKG" "$OUT/monkey.txt"; then
  echo "::error::Monkey found a crash or ANR"
  grep -A20 "CRASH\|NOT RESPONDING" "$OUT/monkey.txt" | head -60
  FAIL=1
fi
alive || { echo "::error::App not running at the end"; FAIL=1; }
[ "$FAIL" = 0 ] && echo "Smoke test passed"
exit $FAIL
