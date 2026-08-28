import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    namespace = "com.gios.lightcamera"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightcamera"
        // AGSL — RuntimeShader in a RenderEffect — is API 33. Every filter in the app is
        // an AGSL shader, so there is no version of this app that runs below it. The LPIII
        // is on Android 14.
        minSdk = 33
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "2.78.1"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key used to live in this repository with its password written three lines
    // under it, which meant anyone at all could build an APK that Android would accept as an
    // update to this one. It is a CI secret now: the workflow decodes it to
    // `keystore/lightcamera.jks`, and that path is git-ignored so a local build cannot put one
    // back by accident.
    //
    // A build without the secret still works and still produces an installable APK — it is
    // signed with the local debug key instead and will not update over a release. That is the
    // right way round: a build that announces it is not the real one beats a build that
    // silently signs itself with a key everybody has.
    val keystoreFile = rootProject.file("keystore/lightcamera.jks")
    val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val canSignRelease = keystoreFile.exists() && keystorePassword.isNotEmpty()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "lightcamera"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // The wheel, the LPIII key map and the LightSync backup provider, shared with every other
    // Light* app rather than pasted into each of them.
    // 1.8.0 for the report stack: the small ReportChip popup, and the sheet fix that keeps
    // the note field above the keyboard. Verified against the package registry's
    // maven-metadata rather than the repo's tags, because a tag over there has been a lie
    // before (v1.5.0 resolved to nothing).
    implementation("com.gios:light-common:1.8.0")
    // Installs the baseline profile that light-common ships in its AAR. Below API 31 nothing
    // reads a profile on its own, so without this the profile is inert and the AOT warm-up it
    // buys never happens.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // CameraX. camera-camera2 is not just the backend here — Camera2Interop is how the
    // hardware face detector is switched on and read back.
    //
    // **1.5 rather than 1.4, for one reason: DNG.** `ImageCapture.OUTPUT_FORMAT_RAW` and
    // `OUTPUT_FORMAT_RAW_JPEG` arrived in 1.5.0, and they are the whole reason Roll can write a
    // negative without standing up a second Camera2 session beside the one CameraX already owns.
    // Two stacks driving one sensor is the kind of thing that works until it doesn't.
    //
    // The bump costs nothing structural: 1.5.3 wants `compileSdk 35` and AGP 8.6, and this module
    // is already on 35 and 8.7.3.
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    // Video mode. Bound instead of ImageCapture rather than alongside it — see CameraEngine.
    implementation("androidx.camera:camera-video:1.5.3")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Shake-to-report posts a GitHub issue. The only network this app does, and only ever
    // after you have tapped SEND on a report you wrote yourself.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR mode. ZXing rather than ML Kit, and not as a preference: ML Kit's barcode reader is
    // delivered through Play Services, which LightOS does not have — it would bind and never
    // answer. This is pure Java, 500 kB, and needs nothing from the platform. See qr/QrAnalyzer.kt.
    implementation("com.google.zxing:core:3.5.3")

    // Reading the words off a photograph on the roll. This is the *bundled* ML Kit artifact —
    // the model is inside the APK. The unbundled one (play-services-mlkit-text-recognition) is
    // delivered through Play Services, which LightOS does not have, so it would bind and never
    // answer, which is exactly why QR above uses ZXing. Costs a few MB; there is no pure-Java
    // text recogniser worth shipping the way there is for barcodes. See ocr/PageReader.kt.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The geometry and the shutter state machine are deliberately free of Android imports,
    // so they can be tested on the JVM rather than on a phone.
    testImplementation("junit:junit:4.13.2")
}
