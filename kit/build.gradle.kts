// What the Ortus Android app does that does not need a screen: the API
// client, the passkey encoding (WebAuthn), the models. Plain JVM, tested
// anywhere.
plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Part of the kit's API (its inline calls decode with them).
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Reading an invitation's QR code from the camera (Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
