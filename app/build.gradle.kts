import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live only in a local, git-ignored keystore.properties (see
// keystore.properties.example) - never hardcoded here and never committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.layerbit.stave"
    compileSdk = 36
    // The Play flavour's models ride in an install-time asset pack (models_pack/); the full flavour
    // has them in its own assets. Both are read as assets/models/<file> (ModelStore).
    assetPacks += listOf(":models_pack")
    // Pinned here and in .github/workflows/native.yml together: the two builds must agree on the
    // toolchain or a library built by one is loaded beside code built by the other.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.layerbit.stave"
        // The family floor Still and Sheaf established. Oboe runs from 16 and AAudio from 26,
        // so 26 is also where low-latency audio stops needing an OpenSL ES fallback.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The four ABIs the native workflow builds libstaveav for; a device outside them
            // would load the app with no decoder, so none is offered.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
            }
        }
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // One source of truth for the two ways the app ships. `play` is small and fetches the large
    // models through Play Asset Delivery (Play's own download, under Play's permission - the
    // app still declares no network). `full` carries every model inside the APK for GitHub
    // Releases and F-Droid, where there is no Play to deliver them.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("full") {
            dimension = "distribution"
            applicationIdSuffix = ""
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) signingConfig = signingConfigs.getByName("release")
        }
        // The release build signed with the debug key, so it installs over a debug build: for
        // measuring frame times and audio glitches on a phone. A debuggable Compose build runs
        // several times slower than a release one, so its jank says little (Squish, 48 ms vs 14).
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        generateLocaleConfig = true
        // Memory-mapped by the model runners, which a compressed asset cannot be.
        noCompress += listOf("tflite", "onnx", "bin", "sf2", "sf3")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The prebuilt libstaveav.so and libstavewhisper.so under jniLibs are already 16 KB
        // aligned (the native build passes max-page-size); the CMake outputs are aligned by AGP.
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

composeCompiler {
    // What may be compared by equals: the read-only collections and the engine's value types
    // (the file says why). Compose compares an unstable parameter by identity under strong
    // skipping, so a new-but-equal list re-ran every clip on Squish's strip every pass.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.media)

    // Everything the source imports directly, declared (tools/check_dependencies.py holds it).
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    // Low-latency audio (Apache-2.0). Prefab: CMake finds it with find_package(oboe).
    implementation(libs.oboe)
    // The model runners' door (Apache-2.0): voice isolation, stems, the tuner, audio-to-MIDI.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// DEPENDENCIES DELIBERATELY NOT HERE
//
// No Media3 player or Transformer: a studio needs a sample-accurate native graph that mixes
// every track in one callback, and Media3 is a player of one stream. Decoding and encoding go
// through libstaveav (FFmpeg, built in CI, committed under jniLibs) and MediaCodec for AAC.
// No networking library of any kind: there is nothing to fetch, and the manifest removes the
// permission so none could be used by accident. No analytics, no crash reporter: the crash log
// is a file in private storage the user may send (ui/pages/ContactScreen.kt).
