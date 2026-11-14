#!/usr/bin/env bash
# T-PERF-09 (NFR-PERF-05), the 1 h half: screen off for an hour with notification access granted, then batterystats,
# jobs and alarms for Strata. Pass: 0 jobs, 0 alarms, 0 wakelocks and 0 wakeups attributable to Strata.
# The jobs/alarms half also runs in CI (BackgroundCostTest in :benchmark).
#
#   tools/perf/background-cost.sh [package] [minutes]     # defaults: app.strata.launcher, 60
set -uo pipefail
pkg="${1:-app.strata.launcher}"
minutes="${2:-60}"
adb get-state >/dev/null 2>&1 || { echo "no device" >&2; exit 2; }
uid=$(adb shell cmd package list packages -U "$pkg" | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1)
[ -n "$uid" ] || { echo "$pkg is not installed" >&2; exit 2; }
out="background-cost-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
echo "T-PERF-09: $pkg (uid $uid), $minutes min screen off -> $out/"
echo "Unplug USB power if you can (wireless adb), or batterystats stops counting while charging."
adb shell dumpsys batterystats --reset >/dev/null
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep $((minutes * 60))
adb shell input keyevent KEYCODE_WAKEUP
adb shell dumpsys batterystats --charged "$pkg" > "$out/batterystats.txt"
adb shell dumpsys jobscheduler > "$out/jobscheduler.txt"
adb shell dumpsys alarm > "$out/alarm.txt"
adb shell dumpsys battery reset
jobs=$(grep "JOB #" "$out/jobscheduler.txt" | grep -c "$pkg/" || true)
alarms=$(grep "Alarm{" "$out/alarm.txt" | grep -c "$pkg" || true)
# batterystats per-uid section: "Wake lock" and "Wakeup alarm" lines for the uid.
wakelocks=$(grep -E "^\s+Wake lock" "$out/batterystats.txt" | grep -vc "realtime 0ms\|(0 times)" || true)
wakeups=$(grep -E "Wakeup alarm" "$out/batterystats.txt" | grep -c "$pkg" || true)
echo "jobs=$jobs alarms=$alarms wakelock-lines=$wakelocks wakeup-alarms=$wakeups (details in $out/)"
if [ "$jobs" -eq 0 ] && [ "$alarms" -eq 0 ] && [ "$wakelocks" -eq 0 ] && [ "$wakeups" -eq 0 ]; then
  echo "T-PERF-09: PASS"; exit 0
fi
echo "T-PERF-09: check $out/batterystats.txt (section for uid $uid)"; exit 1
