#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
android_root="$repo_root/android"

java_home_candidate="${ANDROID_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$java_home_candidate" ]] || [[ ! -x "$java_home_candidate/bin/java" ]]; then
  studio_java_home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [[ -x "$studio_java_home/bin/java" ]]; then
    java_home_candidate="$studio_java_home"
  else
    echo "Food Blob Android requires JDK 17 or newer. Set ANDROID_JAVA_HOME or JAVA_HOME." >&2
    exit 1
  fi
fi

java_major="$("$java_home_candidate/bin/java" -version 2>&1 | awk -F '[\".]' '/version/ { if ($2 == 1) print $3; else print $2; exit }')"
if [[ -z "$java_major" ]] || (( java_major < 17 )); then
  echo "Food Blob Android requires JDK 17 or newer; found Java $java_major at $java_home_candidate." >&2
  exit 1
fi

export JAVA_HOME="$java_home_candidate"
cd "$android_root"
exec ./gradlew --no-daemon "$@"
