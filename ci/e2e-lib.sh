#!/usr/bin/env bash
# The phones' end-to-end library (ortus-chat phones/e2e) for the app, built
# from source at the chat commit the pinned program builds on: its .so
# files (arm64-v8a, armeabi-v7a, x86_64; 16 KB pages, as Android 15 and
# Google Play ask), its Kotlin bindings (UniFFI) and the list of the Rust
# crates inside it with their licences, all under .deps/e2e (not kept in
# git). Bindings and library come from one build. Needs cargo, cargo-ndk
# and the NDK (ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk).
#
# ORTUS_CHAT_REV=<commit> builds another chat commit; ORTUS_CHAT_DIR=<dir>
# builds a local checkout as it is (both for trying a change).
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
program="$(sed -n 's/^ortus = "\([0-9a-f]\{40\}\)"$/\1/p' pins.toml)"
[ -n "$program" ] || { echo "e2e-lib: pins.toml names no ortus commit" >&2; exit 1; }
chat="${ORTUS_CHAT_REV:-$(curl -fsSL "https://raw.githubusercontent.com/Ortus-Sovereign-Cloud/ortus/$program/pins.toml" | sed -n 's/^ortus-chat = "\([0-9a-f]\{40\}\)"$/\1/p')}"
[ -n "$chat" ] || { echo "e2e-lib: the program at $program names no ortus-chat commit" >&2; exit 1; }
out="$root/.deps/e2e"
if [ -z "${ORTUS_CHAT_DIR:-}" ] && [ "$(cat "$out/rev" 2>/dev/null)" = "$chat" ]; then
  echo "e2e-lib: ortus-chat $chat already built"
  exit 0
fi
src="$root/.deps/ortus-chat"
if [ -n "${ORTUS_CHAT_DIR:-}" ]; then
  src="$(cd "$ORTUS_CHAT_DIR" && pwd)"
  chat="local $(git -C "$src" rev-parse HEAD) $(date +%s)"
elif [ "$(git -C "$src" rev-parse HEAD 2>/dev/null)" != "$chat" ]; then
  rm -rf "$src"
  git init -q "$src"
  git -C "$src" fetch -q --depth 1 https://github.com/Ortus-Sovereign-Cloud/ortus-chat "$chat"
  git -C "$src" checkout -q FETCH_HEAD
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK_HOME}"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
rm -rf "$out"
mkdir -p "$out"
cd "$src/phones/e2e"
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
  cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o "$out/jniLibs" build --locked --release -p ortus-e2e-ffi --lib
cargo build --locked -p ortus-e2e-ffi --lib
cargo run --locked --quiet --manifest-path bindgen/Cargo.toml -- generate --no-format \
  --library target/debug/libortus_e2e_ffi.so --language kotlin --out-dir "$out/kotlin"
# name, version, licence of every crate linked into the phone's library.
# (Procedural macros run at build time only: they are not in it.)
cargo tree --locked --color never -p ortus-e2e-ffi -e normal,no-proc-macro --target aarch64-linux-android --prefix none -f '{p}|{l}' \
  | sed -E 's/ \([^)]*\)//g; s/ v([0-9])/|\1/' | LC_ALL=C sort -u > "$out/crates.txt"
echo "$chat" > "$out/rev"
echo "e2e-lib: ortus-chat $chat built into .deps/e2e"
