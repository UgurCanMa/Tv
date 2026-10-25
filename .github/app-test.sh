#!/usr/bin/env bash
# Rauchtest im Android-Emulator: App starten, durch die Bereiche tippen, Wiedergabe prüfen.
set -x
mkdir -p shots

shot() { adb exec-out screencap -p > "shots/$1.png"; }

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml > /dev/null 2>&1
  local xy
  xy=$(python3 - "$1" <<'PY'
import html, re, sys
xml = open('ui.xml', encoding='utf-8').read()
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*?/?>', xml):
    node = m.group(0)
    t = re.search(r' text="([^"]*)"', node)
    if t and html.unescape(t.group(1)).startswith(want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    return 0
  fi
  echo "NICHT GEFUNDEN: $1" | tee -a shots/result.txt
  return 1
}

adb install -r UgurTV.apk 2>&1 | tee shots/install.txt
adb logcat -c
adb shell am start -W -n de.ugur.tv/.MainActivity
sleep 25
shot 1_start

tap_text "Nachrichten & Doku" && sleep 3
shot 2_gruppe
tap_text "DW (Englisch)" && sleep 30
shot 3_live
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 2

tap_text "Filme" && sleep 3
shot 4_filme
tap_text "A–C" && sleep 4
shot 5_filmliste
adb shell input keyevent KEYCODE_BACK
sleep 2

tap_text "Serien" && sleep 3
shot 6_serien
tap_text "Türkçe" && sleep 3
shot 7_tuerkce

tap_text "Suchen" || adb shell input tap 540 150
sleep 1
adb shell input text "Tatort"
sleep 3
shot 8_suche

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
echo "FATAL: $(grep -c 'FATAL EXCEPTION' shots/logcat.txt)" | tee -a shots/result.txt
exit 0
