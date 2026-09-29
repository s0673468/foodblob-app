#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 || $# > 2 )); then
  echo "Usage: scripts/android_device_test.sh <adb-serial> [test-class-or-class#method]" >&2
  exit 2
fi

device_serial="$1"
test_selector="${2:-}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
app_apk="$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$repo_root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
app_id="org.example.foodblob"
test_id="org.example.foodblob.test"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb_path="${sdk_root:+$sdk_root/platform-tools/adb}"
if [[ -z "$adb_path" || ! -x "$adb_path" ]]; then
  adb_path="$(command -v adb || true)"
fi
if [[ -z "$adb_path" || ! -x "$adb_path" ]]; then
  echo "Android platform-tools adb was not found. Set ANDROID_SDK_ROOT or add adb to PATH." >&2
  exit 1
fi

adb_command=("$adb_path" -s "$device_serial")
if [[ "$("${adb_command[@]}" get-state)" != "device" ]]; then
  echo "The requested Android device is not connected and authorized." >&2
  exit 1
fi

"$repo_root/scripts/android_gradle.sh" :app:assembleDebug :app:assembleDebugAndroidTest

instrumentation_log="$(mktemp "${TMPDIR:-/tmp}/foodblob-device-test.XXXXXX")"

cleanup_test_package() {
  "${adb_command[@]}" uninstall "$test_id" >/dev/null 2>&1 || true
  rm -f "$instrumentation_log" || true
}
trap cleanup_test_package EXIT

"${adb_command[@]}" install -r -t "$app_apk"
"${adb_command[@]}" install -r -t "$test_apk"

instrumentation_args=(-w -r -e clearPackageData false)
if [[ -n "$test_selector" ]]; then
  instrumentation_args+=(-e class "$test_selector")
fi
# Preserve the live raw report and adb's transport status. am instrument can
# exit zero even when tests fail, crash, or are all skipped.
set +e
"${adb_command[@]}" shell am instrument \
  "${instrumentation_args[@]}" \
  "$test_id/androidx.test.runner.AndroidJUnitRunner" | tee "$instrumentation_log"
instrumentation_status=("${PIPESTATUS[@]}")
set -e
if (( instrumentation_status[0] != 0 )); then
  exit "${instrumentation_status[0]}"
fi
if (( instrumentation_status[1] != 0 )); then
  exit "${instrumentation_status[1]}"
fi
"${PYTHON:-python3}" "$repo_root/scripts/validate_instrumentation.py" "$instrumentation_log"

"${adb_command[@]}" shell pm path "$app_id" >/dev/null
