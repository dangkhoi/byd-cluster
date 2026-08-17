#!/usr/bin/env bash
# drive-corpus.sh — auto-drive GMaps navigation along many short routes with SMALL GPS steps
# (follows roads better than big jumps; reaches the destination for arrival glyphs) to harvest a
# wide variety of maneuver arrows into NavArrowLog. Routes on stdin: "slat slon dlat dlon [steps] [label]".
# NOTE: every adb call has </dev/null so it does NOT steal the route-list stdin.
set -uo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"; D="${D:-emulator-5554}"
while read -r slat slon dlat dlon steps label; do
  [ -z "${slat:-}" ] && continue
  case "$slat" in \#*) continue;; esac
  steps="${steps:-45}"
  echo ">>> ${label:-route}  ($slat,$slon)->($dlat,$dlon) x$steps"
  "$ADB" -s "$D" emu geo fix "$slon" "$slat" </dev/null >/dev/null 2>&1; sleep 2
  "$ADB" -s "$D" shell am start -a android.intent.action.VIEW -d "google.navigation:q=$dlat,$dlon&mode=d" </dev/null >/dev/null 2>&1
  sleep 4
  dlatp=$(awk "BEGIN{printf \"%.7f\",($dlat-($slat))/$steps}")
  dlonp=$(awk "BEGIN{printf \"%.7f\",($dlon-($slon))/$steps}")
  lat="$slat"; lon="$slon"
  for i in $(seq 1 "$steps"); do
    lat=$(awk "BEGIN{printf \"%.7f\",$lat+($dlatp)}")
    lon=$(awk "BEGIN{printf \"%.7f\",$lon+($dlonp)}")
    "$ADB" -s "$D" emu geo fix "$lon" "$lat" </dev/null >/dev/null 2>&1
    sleep 1.2
  done
done
