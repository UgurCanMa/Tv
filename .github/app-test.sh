#!/usr/bin/env bash
# Rauchtest im Android-Emulator: App starten, durch die Bereiche tippen, Wiedergabe prüfen.
set -x
mkdir -p shots

shot() { adb exec-out screencap -p > "shots/$1.png"; }

find_xy() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml > /dev/null 2>&1
  python3 - "$1" <<'PY'
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
}

tap_text() {
  local xy
  xy=$(find_xy "$1")
  if [ -z "$xy" ] && [ -n "$2" ]; then
    adb shell input swipe 1000 "$2" 150 "$2" 300
    sleep 1
    xy=$(find_xy "$1")
  fi
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    echo "OK: $1" >> shots/result.txt
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

# Gruppe öffnen und wieder zurück
tap_text "Nachrichten & Doku" && sleep 3
shot 2_gruppe
adb shell input keyevent KEYCODE_BACK
sleep 2

# Suche + Live-Wiedergabe (DW läuft weltweit)
tap_text "Suchen" || adb shell input tap 540 240
sleep 1
adb shell input text "DW"
sleep 3
adb shell input keyevent KEYCODE_BACK   # Tastatur zu
sleep 1
shot 3_suche
tap_text "DW (Englisch)" && sleep 35
shot 4_live
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input keyevent KEYCODE_BACK   # Suche leeren
sleep 2

# Filme
tap_text "Filme" 390 && sleep 3
shot 5_filme
tap_text "A–C" && sleep 4
shot 6_filmliste
adb shell input keyevent KEYCODE_BACK
sleep 2

# Serien + eine Folge abspielen (aus den USA meist gesperrt -> Fehlermeldung ist ok)
tap_text "Serien" 390 && sleep 3
shot 7_serien
tap_text "Der Bergdoktor" && sleep 3
shot 8_folgen
adb shell input tap 540 640
sleep 25
shot 9_folge
adb shell input keyevent KEYCODE_BACK
sleep 2

tap_text "Türkçe" 390 && sleep 3
shot 10_tuerkce

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
echo "FATAL: $(grep -c 'FATAL EXCEPTION' shots/logcat.txt)" | tee -a shots/result.txt
exit 0
