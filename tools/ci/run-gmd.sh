#!/usr/bin/env bash
# Runs one Gradle Managed Device smoke task. Retries ONCE, and only when the log shows an infrastructure
# failure (emulator did not boot, adb/device lost, install transport error). A failing test is never retried
# (IMPLEMENTATION_ROADMAP M0 risk: "Orchestrator with retries for infra failures only").
# Usage: tools/ci/run-gmd.sh api34DebugAndroidTest [extra Gradle arguments...]
#        tools/ci/run-gmd.sh :benchmark:api34BenchmarkAndroidTest -P...   (a task path is used as given)
set -uo pipefail

task="${1:?usage: run-gmd.sh <gradle managed-device task, e.g. api34DebugAndroidTest> [gradle args...]}"
shift
case "$task" in
  :*) task_path="$task" ;;
  *) task_path=":app:${task}" ;;
esac
log="$(mktemp)"
stamp="$(mktemp)" # touched when an attempt starts: results newer than it belong to that attempt
infra_pattern='Unable to start Android emulator|Unable to find device serial|Failed to start emulator|emulator.*(timed out|did not boot|crash)|Timed out waiting for .*(device|emulator|boot)|device offline|device .* not found|adb server|INSTALL_FAILED_(INSUFFICIENT_STORAGE|USER_RESTRICTED)|Unable to connect to adb|System image .*(download|unzip)|Connection reset|Read timed out|Failed to install the following SDK components|Archive is not a ZIP archive|Error reading Zip content'

# AGP reports "Error message from emulator process = []" when the emulator dies at once; print what the host knows.
dump_emulator_diagnostics() {
  echo "::group::emulator diagnostics"
  ls -l /dev/kvm 2>&1 || true
  "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/emulator/emulator" -accel-check 2>&1 | head -20 || true
  df -h / 2>&1 | head -5 || true
  # AGP swallows the emulator's own output; start the AVD it just built by hand to see why it exits.
  local sdk avd_home avd
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
  avd_home="$HOME/.android/avd/gradle-managed"
  ls -la "$avd_home" 2>&1 | head -20 || true
  "$sdk/emulator/emulator" -version 2>&1 | head -3 || true
  avd="$(find "$avd_home" -maxdepth 1 -name '*.ini' -printf '%f\n' 2>/dev/null | head -1)"
  avd="${avd%.ini}"
  if [ -n "$avd" ]; then
    echo "--- manual start of $avd (60 s)"
    ANDROID_AVD_HOME="$avd_home" timeout 60 "$sdk/emulator/emulator" -avd "$avd" -no-window -no-audio -no-boot-anim \
      -no-snapshot -read-only -gpu swiftshader_indirect -verbose 2>&1 | tail -n 60 || true
    pkill -f qemu-system || true
    cat "$avd_home/$avd.avd/config.ini" 2>/dev/null | head -60 || true
  fi
  find "$HOME/.android" /tmp -maxdepth 5 \( -name 'emulator*.log' -o -name 'hardware-qemu.ini' -o -name '*.log' \) -newer "$log" 2>/dev/null | head -20 | while read -r f; do
    echo "--- $f"; tail -n 60 "$f"
  done
  echo "::endgroup::"
}

attempt=1
while true; do
  echo "::group::GMD ${task} attempt ${attempt}"
  touch "$stamp"
  ./gradlew "${task_path}" --no-daemon --stacktrace \
    -Pstrata.strictModeDeath=true \
    -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
    -Pandroid.experimental.testOptions.managedDevices.emulator.showKernelLogging=true \
    "$@" \
    2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  echo "::endgroup::"
  if [ "$status" -eq 0 ]; then
    # AGP can report success for a run the Orchestrator recorded as failed: PR #21 run 44227087271 (API 34) had
    # "Test run failed to complete. Expected 85 tests, received 84" (an instrumentation process crashed) in its XML
    # and BUILD SUCCESSFUL, exit code 0. Fail closed: any <failure> or <error> in this attempt's results fails the job.
    module="${task_path#:}"; module="${module%%:*}"
    failed="$(find "$module/build/outputs/androidTest-results" -name 'TEST-*.xml' -newer "$stamp" \
      -exec grep -lE '<(failure|error)[ >]' {} + 2>/dev/null || true)"
    if [ -n "$failed" ]; then
      echo "::error::${task}: Gradle reported success, but the test results record failures: ${failed}"
      grep -hoE '<(failure|error) message="[^"]{0,200}' $failed | head -5 || true
      exit 1
    fi
    exit 0
  fi
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
    # A truncated system-image download (run 41670711767: "Archive is not a ZIP archive") must not be reused.
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
    rm -rf "$sdk/.temp" "$sdk/.downloadIntermediates" 2>/dev/null || true
    # A failed emulator-package install leaves $sdk/emulator without its binary; the retry then failed with "Cannot
    # query the value of this property" instead of installing again (flake hunt run 44190179756, API 26). Remove it so
    # AGP installs it afresh.
    if [ -d "$sdk/emulator" ] && [ ! -x "$sdk/emulator/emulator" ]; then
      echo "::warning::removing the half-installed emulator package at $sdk/emulator before the retry"
      rm -rf "$sdk/emulator"
    fi
    continue
  fi
  echo "::error::${task}: failed for a reason that is not a known infrastructure failure"
  exit "$status"
done
