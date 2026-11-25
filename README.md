# ortus-android

Ortus for Android.

Part of Ortus, a sovereign workspace for organisations and governments: mail, calendar, files, documents, chat, calls, tasks and approvals that an organisation runs itself.

Status: early development, not ready for use.

## Building

`ci/check.sh` runs what CI runs. With an Android SDK (`ANDROID_HOME`) it
also builds the app, which needs the end-to-end library for its encrypted
chat rooms: `ci/e2e-lib.sh` builds it from source (ortus-chat
`phones/e2e`, at the chat commit the pinned Ortus program builds on) into
`.deps/e2e`. That needs Rust with the Android targets
(`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`),
`cargo install cargo-ndk` and the NDK.

## Licence

Licensed under either of the Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE)) or the MIT license ([LICENSE-MIT](LICENSE-MIT)), at your option.

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in this work, as defined in the Apache-2.0 licence, shall be dual licensed as above, without any additional terms or conditions.
