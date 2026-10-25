#!/usr/bin/env bash
# Rauchtest im Android-Emulator: App starten, durch die Bereiche tippen, Wiedergabe prüfen,
# eigene Listen per „Teilen“ hinzufügen.
set -x
mkdir -p shots

shot() { adb exec-out screencap -p > "shots/$1.png"; }

find_xy() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml > /dev/null 2>&1
  python3 - "$1" <<'PY'
import html, re, sys
xml = open('ui.xml', encoding='utf-8').read()
want = sys.argv[1].lower()
for m in re.finditer(r'<node [^>]*?/?>', xml):
    node = m.group(0)
    t = re.search(r' text="([^"]*)"', node)
    if t and html.unescape(t.group(1)).lower().startswith(want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}

# tap_text TEXT [Y-Position der Reiterleiste zum Wischen]
tap_text() {
  local xy
  xy=$(find_xy "$1")
  local tries=0
  while [ -z "$xy" ] && [ -n "$2" ] && [ $tries -lt 3 ]; do
    adb shell input swipe 1000 "$2" 150 "$2" 300
    sleep 1
    xy=$(find_xy "$1")
    tries=$((tries + 1))
  done
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    echo "OK: $1" >> shots/result.txt
    return 0
  fi
  echo "NICHT GEFUNDEN: $1" | tee -a shots/result.txt
  return 1
}

share_link() {
  adb shell am start -W -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$1" --es android.intent.extra.SUBJECT "$2" \
    -n de.ugur.tv/.SourcesActivity
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

# Serien + eine Folge abspielen
tap_text "Serien" 390 && sleep 3
shot 7_serien
tap_text "Der Bergdoktor" && sleep 3
shot 8_folgen
adb shell input tap 540 790
sleep 25
shot 9_folge
adb shell input keyevent KEYCODE_BACK
sleep 2

tap_text "Türkçe" 390 && sleep 3
shot 10_tuerkce

# Eigene Listen per „Teilen“: ein einzelner Stream und eine ganze Liste
share_link "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8" "DW-Stream"
sleep 4
shot 11_teilen_dialog
tap_text "Hinzufügen"
sleep 2
share_link "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages/countries/ch.m3u" "Schweiz"
sleep 4
tap_text "Hinzufügen"
sleep 2
shot 12_meine_listen
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 15
shot 13_neue_reiter
tap_text "DW-Stream" 390 && sleep 3
shot 14_reiter_stream
adb shell input tap 540 620
sleep 30
shot 15_stream_play
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "Schweiz" 390 && sleep 4
shot 16_reiter_liste

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
echo "FATAL: $(grep -c 'FATAL EXCEPTION' shots/logcat.txt)" | tee -a shots/result.txt
exit 0
