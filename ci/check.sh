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
# The Play listing in every language, within Play's limits.
for lang in en pl uk de fr ar; do
  for f in title:30 short_description:80 full_description:4000; do
    file="store/android/listing/$lang/${f%%:*}.txt"
    [ -s "$file" ] || { echo "check: $file is missing" >&2; exit 1; }
    [ "$(tr -d '\n' < "$file" | wc -m)" -le "${f##*:}" ] || { echo "check: $file is over ${f##*:} characters" >&2; exit 1; }
  done
done
# Dependencies are Apache-2.0 or MIT (AndroidX, Kotlin, kotlinx, OkHttp,
# ZXing core: reviewed 2026-11-21, Apache-2.0, pure Java, no native code);
# a new one needs a licence review.
deps="$(grep -ho '"[a-z][a-zA-Z0-9.-]*:[a-zA-Z0-9.-]*:\?[^"]*"' kit/build.gradle.kts app/build.gradle.kts | tr -d '"' | cut -d: -f1 | sort -u)"
for group in $deps; do
  case "$group" in
    androidx.*|org.jetbrains.kotlinx|com.squareup.okhttp3|com.google.zxing) ;;
    *) echo "check: $group needs a licence review" >&2; exit 1 ;;
  esac
done
./gradlew :kit:test -q
if [ -n "${ANDROID_HOME:-}" ]; then
  ./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest -q
  # Every library the app ships, with its licence, kept current.
  python3 tools/licences.py > build/LICENSES.md
  cmp -s build/LICENSES.md LICENSES.md || { diff -u LICENSES.md build/LICENSES.md >&2 || true; echo "check: LICENSES.md is stale: python3 tools/licences.py > LICENSES.md" >&2; exit 1; }
else
  echo "check: no Android SDK here; the app, its licence list and its emulator tests run in CI"
fi
echo "all checks passed"
