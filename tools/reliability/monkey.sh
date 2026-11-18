#!/usr/bin/env bash
# T-REL-01 (NFR-REL-01): the 1 h monkey on a device or an API 34 emulator. Fails (exit 1) on any crash or ANR.
#
#   tools/reliability/monkey.sh                      # 70 000 events, throttle 50 ms (~1 h), app.strata.launcher
#   tools/reliability/monkey.sh -e 2000 -t 100       # a short run
#   tools/reliability/monkey.sh -p app.strata.launcher.benchmark -s 7
#
# Preconditions (TEST_PLAN T-REL-01): APPS500 or the owner's apps, one widget on home, 5 themes in the library.
# Strata should be the default home, so Home key presses return to it. Keep the phone on charge and the screen awake
# (Developer options -> Stay awake). Monkey events stay inside the package: other apps' activities are refused.
# The output, logcat crash buffer and ANR traces list are saved under ./monkey-<timestamp>/.
set -uo pipefail

pkg="app.strata.launcher"
events=70000
throttle=50
seed=$RANDOM
while getopts "p:e:t:s:" opt; do
  case "$opt" in
    p) pkg="$OPTARG" ;;
    e) events="$OPTARG" ;;
    t) throttle="$OPTARG" ;;
    s) seed="$OPTARG" ;;
    *) echo "usage: $0 [-p package] [-e events] [-t throttle-ms] [-s seed]" >&2; exit 2 ;;
  esac
done

adb get-state >/dev/null 2>&1 || { echo "no device: connect one (adb devices)" >&2; exit 2; }
adb shell pm path "$pkg" >/dev/null 2>&1 || { echo "$pkg is not installed" >&2; exit 2; }

out="monkey-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
echo "T-REL-01: $pkg, $events events, throttle $throttle ms, seed $seed -> $out/"
adb logcat -c
# --pct-syskeys 0: no call/end-call/volume keys (end-call can turn the screen off). Back and Menu stay (major nav).
# --ignore-crashes/--ignore-timeouts: keep going past another app's crash or ANR (still reported); only $pkg's fail.
adb shell monkey -p "$pkg" -s "$seed" --throttle "$throttle" --pct-syskeys 0 --ignore-crashes --ignore-timeouts \
  --monitor-native-crashes -v "$events" > "$out/monkey.txt" 2>&1
adb logcat -d -b crash > "$out/logcat-crash.txt" 2>&1 || true
adb logcat -d -s AndroidRuntime:E ActivityManager:E > "$out/logcat-errors.txt" 2>&1 || true

# Reports for $pkg's own processes ($pkg, $pkg:*) fail the run; the same rule as MonkeyVerdict in the benchmark module.
pkg_re=$(printf '%s' "$pkg" | sed 's/\./\\./g') # dots in the package name match only dots
crashes=$(grep -c -E "// (NATIVE )?CRASH:? ${pkg_re}[ :]" "$out/monkey.txt" || true)
anrs=$(grep -c -E "// NOT RESPONDING: ${pkg_re}[ :]" "$out/monkey.txt" || true)
others=$(grep -E "// (NATIVE )?CRASH|// NOT RESPONDING" "$out/monkey.txt" | grep -v -E " ${pkg_re}[ :]" || true)
[ -n "$others" ] && printf 'other processes (not counted):\n%s\n' "$others"
fatal=$(grep -c "Process: $pkg, PID" "$out/logcat-crash.txt" || true)
finished=$(grep -c "// Monkey finished" "$out/monkey.txt" || true)
echo "finished=$finished crashes=$crashes anrs=$anrs fatal-in-logcat=$fatal (seed $seed)"
if [ "$finished" -ge 1 ] && [ "$crashes" -eq 0 ] && [ "$anrs" -eq 0 ] && [ "$fatal" -eq 0 ]; then
  echo "T-REL-01: PASS"
  exit 0
fi
echo "T-REL-01: FAIL (see $out/; rerun the same input with -s $seed)"
exit 1
