// What the Ortus Android app does that does not need a screen: the API
// client, the passkey encoding (WebAuthn), the models. Plain JVM, tested
// anywhere.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
