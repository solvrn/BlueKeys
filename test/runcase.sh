#!/bin/bash
# runcase.sh <editor> <file> — opens a fresh test-editor tab in Chrome (closing only
# earlier test tabs), types <file> with BlueKeys and saves what the editor got.
ED=$1; T=$(cat "$2")
adb shell "am start -n app.bluekeys/.MainActivity --es text '$T' --ei gap ${GAP:-15}" >/dev/null 2>&1
rm -f result-$ED.txt
osascript -e 'tell application "Google Chrome"
  repeat with w in windows
    repeat with i from (count of tabs of w) to 1 by -1
      if URL of tab i of w starts with "http://127.0.0.1:8765/" then close tab i of w
    end repeat
  end repeat
end tell'
open -a "Google Chrome" "http://127.0.0.1:8765/editor.html?ed=$ED"
for i in $(seq 1 20); do sleep 1; [ -f result-$ED.txt ] && break; done
sleep 2
FRONT=$(osascript -e 'tell application "System Events" to get name of first application process whose frontmost is true')
URL=$(osascript -e 'tell application "Google Chrome" to get URL of active tab of front window')
case "$FRONT|$URL" in "Google Chrome|http://127.0.0.1:8765/"*) ;; *) echo "ABORT: front is $FRONT $URL"; exit 1;; esac
./tap.sh typeBtn
last=-1; same=0
for i in $(seq 1 60); do sleep 2; n=$(wc -c < result-$ED.txt | tr -d ' ')
  if [ "$n" = "$last" ] && [ "$n" -gt 0 ]; then same=$((same+1)); [ $same -ge 2 ] && break; else same=0; fi; last=$n; done
if diff "$2" <(cat result-$ED.txt; echo); then echo "$ED: IDENTICAL"; fi
