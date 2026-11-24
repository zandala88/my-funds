#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
funds_tools="${MY_FUNDS_TOOLS:-$HOME/.cache/my-funds-tools}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$funds_tools/jdk/Contents/Home" ]; then
  export JAVA_HOME="$funds_tools/jdk/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
exec ./gradlew "$@"
