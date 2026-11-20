pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ortus-android"
include(":kit")
// The app needs the Android SDK: built where it is (CI, Android Studio).
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":app")
}
