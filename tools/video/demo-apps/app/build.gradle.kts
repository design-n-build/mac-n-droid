plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.macndroid.demo"
    compileSdk = 36
    defaultConfig { minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "1" }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    // One app per invented sender: Android shows the posting app's own name and icon on a notification.
    flavorDimensions += "sender"
    productFlavors {
        fun sender(name: String, label: String, kind: String, color: String) = create(name) {
            dimension = "sender"
            applicationId = "app.macndroid.demo.$name"
            resValue("string", "app_name", label)
            resValue("color", "brand", color)
            buildConfigField("String", "KIND", "\"$kind\"")
        }
        sender("messages", "Messages", "text", "#1A73E8")
        sender("bank", "Northwind Bank", "code", "#4F46E5")
        sender("files", "Files", "download", "#0F9D58")
    }
}

// Debug only: these are props for recording, never something to ship.
androidComponents { beforeVariants { it.enable = it.buildType == "debug" } }
