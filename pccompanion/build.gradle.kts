// DSF PC COMPANION (natyo2's fork): DS Forever's companion ALONE, as a small app of its own. WoW runs on the PC; another streamer
// (Apollo + Artemis) shows it on the Thor's top screen; this app is the companion on the bottom screen (PcCompanionActivity, in
// :dsforever), fed by DS Forever Desktop's bridge. No launcher, no container, no Proton: none of :app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "app.dsforever.pccompanion"
    compileSdk = 36
    defaultConfig {
        // its own package: installs beside (never over) a DS Forever, and needs none
        applicationId = "app.dsforever.pccompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.16.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // the launcher icon (DS Forever's own mark) from the companion's resources
    sourceSets["main"].res.srcDirs("${rootDir}/companion/app/src/main/res")
    buildTypes {
        release {
            isMinifyEnabled = false
            // a personal build: the machine's debug key (keep ~/.android/debug.keystore, or updates need a reinstall)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":dsforever"))
}
