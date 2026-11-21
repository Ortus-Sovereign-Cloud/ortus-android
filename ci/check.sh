#!/usr/bin/env bash
# Everything that can be checked on this machine: the shared kit (any
# JVM), the texts in six languages, and, with the Android SDK here, the
# app's build. The emulator tests run in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
for script in ci/*.sh; do bash -n "$script"; done
for lang in pl uk de fr ar; do
  diff <(grep -o 'name="[^"]*"' app/src/main/res/values/strings.xml | sort) <(grep -o 'name="[^"]*"' "app/src/main/res/values-$lang/strings.xml" | sort) > /dev/null \
    || { echo "check: $lang texts differ from en" >&2; exit 1; }
done
# Dependencies are Apache-2.0 or MIT (AndroidX, Kotlin, kotlinx, OkHttp);
# a new one needs a licence review.
deps="$(grep -ho '"[a-z][a-zA-Z0-9.-]*:[a-zA-Z0-9.-]*:\?[^"]*"' kit/build.gradle.kts app/build.gradle.kts | tr -d '"' | cut -d: -f1 | sort -u)"
for group in $deps; do
  case "$group" in
    androidx.*|org.jetbrains.kotlinx|com.squareup.okhttp3) ;;
    *) echo "check: $group needs a licence review" >&2; exit 1 ;;
  esac
done
./gradlew :kit:test -q
if [ -n "${ANDROID_HOME:-}" ]; then ./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest -q; else echo "check: no Android SDK here; the app and its emulator tests run in CI"; fi
echo "all checks passed"
