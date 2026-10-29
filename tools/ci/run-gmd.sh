#!/usr/bin/env bash
# Runs one Gradle Managed Device smoke task. Retries ONCE, and only when the log shows an infrastructure
# failure (emulator did not boot, adb/device lost, install transport error). A failing test is never retried
# (IMPLEMENTATION_ROADMAP M0 risk: "Orchestrator with retries for infra failures only").
# Usage: tools/ci/run-gmd.sh api34DebugAndroidTest
set -uo pipefail

task="${1:?usage: run-gmd.sh <gradle managed-device task, e.g. api34DebugAndroidTest>}"
log="$(mktemp)"
infra_pattern='Unable to start Android emulator|Unable to find device serial|Failed to start emulator|emulator.*(timed out|did not boot|crash)|Timed out waiting for .*(device|emulator|boot)|device offline|device .* not found|adb server|INSTALL_FAILED_(INSUFFICIENT_STORAGE|USER_RESTRICTED)|Unable to connect to adb|System image .*(download|unzip)|Connection reset|Read timed out'

# AGP reports "Error message from emulator process = []" when the emulator dies at once; print what the host knows.
dump_emulator_diagnostics() {
  echo "::group::emulator diagnostics"
  ls -l /dev/kvm 2>&1 || true
  "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/emulator/emulator" -accel-check 2>&1 | head -20 || true
  df -h / 2>&1 | head -5 || true
  find "$HOME/.android" /tmp -maxdepth 4 \( -name 'emulator*.log' -o -name 'hardware-qemu.ini' -o -name '*.log' \) -newer "$log" 2>/dev/null | head -20 | while read -r f; do
    echo "--- $f"; tail -n 60 "$f"
  done
  echo "::endgroup::"
}

attempt=1
while true; do
  echo "::group::GMD ${task} attempt ${attempt}"
  ./gradlew ":app:${task}" --no-daemon --stacktrace \
    -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
    -Pandroid.experimental.testOptions.managedDevices.emulator.showKernelLogging=true \
    2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  echo "::endgroup::"
  [ "$status" -eq 0 ] && exit 0
  dump_emulator_diagnostics

  if [ "$attempt" -ge 2 ]; then
    echo "::error::${task} failed after ${attempt} attempts"
    exit "$status"
  fi
  # A test assertion/failure line means the app is at fault: do not retry.
  if grep -qE 'There were failing tests|Test run failed|AssertionError|tests completed, [0-9]+ failed' "$log"; then
    echo "::error::${task}: test failure, not retrying"
    exit "$status"
  fi
  if grep -qEi "$infra_pattern" "$log"; then
    echo "::warning::${task}: infrastructure failure detected, retrying once"
    attempt=$((attempt + 1))
    ./gradlew --stop >/dev/null 2>&1 || true
    continue
  fi
  echo "::error::${task}: failed for a reason that is not a known infrastructure failure"
  exit "$status"
done
