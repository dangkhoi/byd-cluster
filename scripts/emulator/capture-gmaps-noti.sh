#!/usr/bin/env bash
# Capture REAL Google Maps navigation notification TEXT fields from a running emulator (or any adb device),
# with zero build and no app install. Uses `dumpsys notification --noredact`, which exposes the full extras
# bundle (android.title / android.text / android.subText / android.bigText / template / smallIcon ref).
#
# Purpose (ground-truth, no-assumptions): answer decisively "does GMaps put a turn VERB in the notification
# text, and in which field?" — the single biggest unknown behind the maneuver classifier. Pairs with the
# arrow-BITMAP capture (see scripts/emulator/README.md → ClusterNav release APK + NavArrowLog verbose).
#
# Usage:
#   scripts/emulator/capture-gmaps-noti.sh [--serial S] [--seconds N] [--interval N] [--out DIR] [--self-test]
#     --serial S     adb serial (default: the single connected device; e.g. emulator-5554)
#     --seconds N    duration; 0 = until Ctrl-C (default: 0)
#     --interval N   snapshot interval seconds (default: 2)
#     --out DIR      output dir (default: ./gmaps-noti-capture-<stamp>)
#     --self-test    validate args + adb presence, do not poll the device
#
# Drive GMaps into active turn-by-turn navigation FIRST (route with left/right/roundabout turns), then run
# this. Every time a field changes, the new snapshot is appended so you get the whole turn sequence.
set -uo pipefail

SDK_ADB="${HOME}/Library/Android/sdk/platform-tools/adb"
ADB_BIN="${ADB:-$SDK_ADB}"; [[ -x "$ADB_BIN" ]] || ADB_BIN="adb"
PKG="com.google.android.apps.maps"
SERIAL=""; DURATION=0; INTERVAL=2; OUT=""; SELF_TEST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)   [[ $# -ge 2 ]] || { echo "ERROR: --serial needs a value" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --seconds)  [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || { echo "ERROR: --seconds needs an integer" >&2; exit 2; }; DURATION="$2"; shift 2 ;;
    --interval) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --interval needs a positive integer" >&2; exit 2; }; INTERVAL="$2"; shift 2 ;;
    --out)      [[ $# -ge 2 && -n "$2" ]] || { echo "ERROR: --out needs a dir" >&2; exit 2; }; OUT="$2"; shift 2 ;;
    --package)  [[ $# -ge 2 && "$2" =~ ^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$ ]] || { echo "ERROR: bad --package" >&2; exit 2; }; PKG="$2"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ "$SELF_TEST" -eq 1 ]]; then
  command -v "$ADB_BIN" >/dev/null 2>&1 && echo "SELF_TEST=PASS adb=$ADB_BIN pkg=$PKG interval=$INTERVAL" \
    || echo "SELF_TEST=WARN adb not found at '$ADB_BIN' (pass ADB=/path or add to PATH)"
  exit 0
fi

command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "ERROR: adb not found: $ADB_BIN (set ADB=/path/to/adb)" >&2; exit 2; }
command -v shasum   >/dev/null 2>&1 || { echo "ERROR: shasum not found" >&2; exit 2; }

# Resolve device (portable: no mapfile — works on macOS default bash 3.2)
if [[ -z "$SERIAL" ]]; then
  DEVS="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1}')"
  N="$(printf '%s\n' "$DEVS" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$N" -eq 1 ]] || { echo "ERROR: expected exactly one device; pass --serial (got: ${DEVS:-none})" >&2; exit 4; }
  SERIAL="$(printf '%s\n' "$DEVS" | sed '/^$/d' | head -1)"
fi
ADB=("$ADB_BIN" -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "ERROR: device not ready: $SERIAL" >&2; exit 4; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT:-./gmaps-noti-capture-$STAMP}"
mkdir -p "$OUT"; chmod 700 "$OUT"
HIST="$OUT/noti-history.txt"; SUM="$OUT/summary.txt"; RAWFULL="$OUT/first-full-dump.txt"
: > "$HIST"

echo "Capturing $PKG notifications from $SERIAL — Ctrl-C to stop. Out: $OUT"
echo "Make sure GMaps is in ACTIVE navigation (a route with turns) before/while this runs."

# One full raw dump up front (for cross-reference of ALL extras keys)
"${ADB[@]}" shell dumpsys notification --noredact > "$RAWFULL" 2>&1 || true

start=$(date +%s); rounds=0; changes=0; prev_hash=""
trap 'echo; echo "Stopped."; ' INT TERM
while :; do
  rounds=$((rounds+1))
  # Extract the block around the GMaps package from the notification dump (title/text/sub/big live in extras=).
  block="$("${ADB[@]}" shell dumpsys notification --noredact 2>/dev/null \
            | grep -iE -A 60 "pkg=$PKG|packageName=$PKG" \
            | grep -iE 'pkg=|when=|android\.title|android\.text|android\.subText|android\.bigText|android\.template|tickerText|category=|smallIcon|largeIcon|extras=|=Bundle' \
            | head -n 80 || true)"
  if [[ -n "$block" ]]; then
    h="$(printf '%s' "$block" | shasum -a 256 | awk '{print $1}')"
    if [[ "$h" != "$prev_hash" ]]; then
      prev_hash="$h"; changes=$((changes+1))
      { printf '\n===== %s · change #%d =====\n' "$(date -u +%FT%TZ)" "$changes"; printf '%s\n' "$block"; } >> "$HIST"
      # Surface a one-line hint live: any turn-verb token seen?
      verb="$(printf '%s' "$block" | grep -ioE 'turn left|turn right|slight|sharp|roundabout|u-?turn|keep left|keep right|merge|rẽ trái|rẽ phải|quẹo|vòng xuyến|chếch|đi thẳng' | head -1 || true)"
      printf '  change #%d  verb-token: %s\n' "$changes" "${verb:-<none>}"
    fi
  fi
  [[ "$DURATION" -ne 0 && "$(date +%s)" -ge $((start+DURATION)) ]] && break
  sleep "$INTERVAL"
done

{
  echo "package=$PKG"; echo "serial=$SERIAL"; echo "rounds=$rounds"; echo "distinct_changes=$changes"
  echo "verb_token_changes=$(grep -c 'verb-token: ' "$HIST" 2>/dev/null || echo 0)"
  echo "history=$HIST"; echo "first_full_dump=$RAWFULL"
} > "$SUM"
echo; echo "Done: $OUT"; cat "$SUM"
