#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo "Usage: scripts/android_release_install.sh <adb-serial>" >&2
  exit 2
fi

device_serial="$1"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
app_id="org.example.foodblob"
track_id="com.health.track"
release_apk="$repo_root/android/app/build/outputs/apk/release/app-release.apk"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

adb_path="${sdk_root:+$sdk_root/platform-tools/adb}"
apksigner_path="${sdk_root:+$sdk_root/build-tools/36.0.0/apksigner}"
aapt2_path="${sdk_root:+$sdk_root/build-tools/36.0.0/aapt2}"
apksig_jar="${sdk_root:+$sdk_root/build-tools/36.0.0/lib/apksigner.jar}"
for tool_name in adb apksigner aapt2; do
  tool_variable="${tool_name}_path"
  tool_path="${!tool_variable:-}"
  if [[ -z "$tool_path" || ! -x "$tool_path" ]]; then
    echo "Android SDK $tool_name was not found. Set ANDROID_SDK_ROOT to an SDK with build-tools 36.0.0." >&2
    exit 1
  fi
done
java_home_candidate="${ANDROID_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$java_home_candidate" || ! -x "$java_home_candidate/bin/java" ]]; then
  studio_java_home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [[ -x "$studio_java_home/bin/java" ]]; then
    java_home_candidate="$studio_java_home"
  fi
fi
if [[ -z "$java_home_candidate" || ! -x "$java_home_candidate/bin/java" || ! -f "$apksig_jar" ]]; then
  echo "Android signing-lineage verification requires JDK 17+ and the Build Tools 36 apksig library." >&2
  exit 1
fi
java_major="$("$java_home_candidate/bin/java" -version 2>&1 | awk -F '[\".]' '/version/ { if ($2 == 1) print $3; else print $2; exit }')"
if [[ -z "$java_major" ]] || (( java_major < 17 )); then
  echo "Android signing-lineage verification requires JDK 17 or newer." >&2
  exit 1
fi

adb_command=("$adb_path" -s "$device_serial")
if [[ "$("${adb_command[@]}" get-state)" != "device" ]]; then
  echo "The requested Android device is not connected and authorized." >&2
  exit 1
fi

device_model="$("${adb_command[@]}" shell getprop ro.product.model | tr -d '\r')"
device_fingerprint="$("${adb_command[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
device_sdk="$("${adb_command[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ -z "$device_model" || -z "$device_fingerprint" || ! "$device_sdk" =~ ^[0-9]+$ || "$device_sdk" -lt 1 ]]; then
  echo "Unable to verify the target Android device identity." >&2
  exit 1
fi

"$repo_root/scripts/android_gradle.sh" :app:assembleRelease
if [[ ! -f "$release_apk" ]]; then
  echo "Release APK was not produced." >&2
  exit 1
fi

badging="$($aapt2_path dump badging "$release_apk")"
if [[ "$badging" =~ versionCode=\'([0-9]+)\' ]]; then
  built_version_code="${BASH_REMATCH[1]}"
else
  echo "Unable to read the release version code." >&2
  exit 1
fi
if [[ "$badging" =~ versionName=\'([^\']+)\' ]]; then
  built_version_name="${BASH_REMATCH[1]}"
else
  echo "Unable to read the release version name." >&2
  exit 1
fi

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/foodblob-release-install.XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT

certificate_digest() {
  "$apksigner_path" verify --max-sdk-version "$device_sdk" --print-certs "$1" \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print $2; exit }'
}

normalize_digest() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

built_digest="$(certificate_digest "$release_apk")"
if [[ -z "$built_digest" ]]; then
  echo "Release APK is unsigned or its signing certificate cannot be verified." >&2
  exit 1
fi

installed_apk_path() {
  local package_name="$1"
  local package_paths
  package_paths="$("${adb_command[@]}" shell pm path "$package_name" 2>/dev/null || true)"
  printf '%s\n' "$package_paths" | tr -d '\r' | sed -n '1s/^package://p'
}

verify_installed_anchor() {
  local package_name="$1"
  local label="$2"
  local compatibility_mode="$3"
  local package_path
  package_path="$(installed_apk_path "$package_name")"
  if [[ -z "$package_path" ]]; then
    return
  fi
  anchor_count=$((anchor_count + 1))
  "${adb_command[@]}" pull "$package_path" "$temporary_dir/$label.apk" >/dev/null
  if ! "$java_home_candidate/bin/java" \
      -cp "$apksig_jar" \
      "$repo_root/scripts/ApkSignatureCompatibility.java" \
      "$compatibility_mode" \
      "$device_sdk" \
      "$release_apk" \
      "$temporary_dir/$label.apk"; then
    echo "Refusing installation because the release signer is not compatible with installed $label." >&2
    exit 1
  fi
}

anchor_count=0
expected_digest="${FOODBLOB_EXPECTED_SIGNER_SHA256:-}"
if [[ -n "$expected_digest" ]]; then
  if [[ ! "$expected_digest" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "FOODBLOB_EXPECTED_SIGNER_SHA256 must be one SHA-256 certificate digest." >&2
    exit 1
  fi
  anchor_count=$((anchor_count + 1))
  if [[ "$(normalize_digest "$expected_digest")" != "$(normalize_digest "$built_digest")" ]]; then
    echo "Refusing installation because the release signer does not match the expected trust anchor." >&2
    exit 1
  fi
fi

installed_path="$(installed_apk_path "$app_id")"
before_first_install=""
if [[ -n "$installed_path" ]]; then
  package_before="$("${adb_command[@]}" shell dumpsys package "$app_id")"
  if [[ "$package_before" =~ versionCode=([0-9]+) ]]; then
    installed_version_code="${BASH_REMATCH[1]}"
  else
    echo "Unable to read the installed Food Blob version." >&2
    exit 1
  fi
  if (( built_version_code < installed_version_code )); then
    echo "Refusing to downgrade Food Blob from versionCode $installed_version_code to $built_version_code." >&2
    exit 1
  fi
  before_first_install="$(printf '%s\n' "$package_before" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -n 1)"
fi

verify_installed_anchor "$app_id" "installed Food Blob" "installed-data"
verify_installed_anchor "$track_id" "installed Track" "signature-permission"
if (( anchor_count == 0 )); then
  echo "No trusted Food Blob signing anchor is available. Install matching Track first or set FOODBLOB_EXPECTED_SIGNER_SHA256." >&2
  exit 1
fi

"${adb_command[@]}" install -r "$release_apk"

package_after="$("${adb_command[@]}" shell dumpsys package "$app_id")"
if [[ ! "$package_after" =~ versionCode=([0-9]+) ]] || [[ "${BASH_REMATCH[1]}" != "$built_version_code" ]]; then
  echo "Installed versionCode does not match the release APK." >&2
  exit 1
fi
if [[ ! "$package_after" =~ versionName=([^[:space:]]+) ]] || [[ "${BASH_REMATCH[1]}" != "$built_version_name" ]]; then
  echo "Installed versionName does not match the release APK." >&2
  exit 1
fi
package_flags="$(printf '%s\n' "$package_after" | awk '/^[[:space:]]*(pkgFlags|flags)=/ { print; exit }')"
if [[ "$package_flags" == *DEBUGGABLE* ]]; then
  echo "Installed Food Blob is unexpectedly debuggable." >&2
  exit 1
fi
after_first_install="$(printf '%s\n' "$package_after" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -n 1)"
if [[ -n "$before_first_install" && "$before_first_install" != "$after_first_install" ]]; then
  echo "First-install time changed; the data-preserving replacement invariant was violated." >&2
  exit 1
fi

after_path="$("${adb_command[@]}" shell pm path "$app_id" | tr -d '\r' | sed -n '1s/^package://p')"
"${adb_command[@]}" pull "$after_path" "$temporary_dir/installed-after.apk" >/dev/null
after_digest="$(certificate_digest "$temporary_dir/installed-after.apk")"
if [[ "$after_digest" != "$built_digest" ]]; then
  echo "Installed APK certificate does not match the verified release artifact." >&2
  exit 1
fi

echo "Installed Food Blob $built_version_name (versionCode $built_version_code) data-preservingly."
echo "Device: $device_model"
echo "Fingerprint: $device_fingerprint"
echo "Signer, package version, non-debuggable status, and first-install continuity verified."
