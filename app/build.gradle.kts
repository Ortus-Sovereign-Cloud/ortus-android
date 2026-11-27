plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "cc.ortus.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cc.ortus.app"
        minSdk = 30
        targetSdk = 35
        // Each release a higher number (CI passes its run number).
        versionCode = (System.getenv("ORTUS_VERSION_CODE") ?: "1").toInt()
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The phone tests reach an Ortus on the CI machine.
        testInstrumentationRunnerArguments["ortusServer"] = System.getenv("ORTUS_SERVER") ?: ""
        testInstrumentationRunnerArguments["ortusCode"] = System.getenv("ORTUS_CODE") ?: ""
        testInstrumentationRunnerArguments["ortusLinkCode"] = System.getenv("ORTUS_LINK_CODE") ?: ""
        testInstrumentationRunnerArguments["ortusAdminCode"] = System.getenv("ORTUS_ADMIN_CODE") ?: ""
        testInstrumentationRunnerArguments["ortusImportPasswords"] = System.getenv("ORTUS_IMPORT_PASSWORDS") ?: ""
        testInstrumentationRunnerArguments["ortusOwnerSession"] = System.getenv("ORTUS_OWNER_SESSION") ?: ""
        // The owner's authenticator secret: a phone sign-in code needs a
        // session of the last ten minutes, which the tests make afresh.
        testInstrumentationRunnerArguments["ortusOwnerTotp"] = System.getenv("ORTUS_OWNER_TOTP") ?: ""
        testInstrumentationRunnerArguments["ortusScreenshotSession"] = System.getenv("ORTUS_SCREENSHOT_SESSION") ?: ""
        // In CI the phone steps must run: a missing value fails them.
        testInstrumentationRunnerArguments["ortusLive"] = if (System.getenv("CI") == "true") "1" else ""
    }
    // The Play upload key, only where a release is built (its keystore and
    // passwords come from CI secrets; nothing secret is in the repository).
    val upload = System.getenv("ORTUS_UPLOAD_KEYSTORE")?.takeIf { file(it).exists() }
    if (upload != null) {
        signingConfigs {
            create("upload") {
                storeFile = file(upload)
                storePassword = System.getenv("ORTUS_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("ORTUS_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("ORTUS_UPLOAD_KEY_PASSWORD")
            }
        }
        buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("upload") } }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // End-to-end rooms: the library built by ci/e2e-lib.sh (Rust, from the
    // chat commit the pinned program builds on), its Kotlin bindings and .so files.
    sourceSets { getByName("main") { java.srcDir("$rootDir/.deps/e2e/kotlin"); jniLibs.srcDir("$rootDir/.deps/e2e/jniLibs") } }
}

dependencies {
    implementation(project(":kit"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The camera for an invitation's QR code (Jetpack, Apache-2.0).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Notifications without Google services (UnifiedPush, Apache-2.0; 2.x
    // needs only the Kotlin library, 3.x brings Tink and protobuf).
    implementation("org.unifiedpush.android:connector:2.5.0")
    // Calls natively: Google's WebRTC (BSD-3-Clause; the owner allowed it
    // 2026-11-22), as built by webrtc-sdk (Google publishes no Android
    // builds any more). 150.7871.04 of 2026-10-23.
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    // What the end-to-end library's Kotlin bindings call it through (JNA,
    // Apache-2.0 OR LGPL-2.1+: taken under Apache-2.0).
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
