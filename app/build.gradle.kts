plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Builds the on-device VLM engine. Enabled by default because a release APK
 * without it cannot honour the "Use local AI" switch.
 *
 * The first configure clones and compiles llama.cpp, which takes roughly fifteen
 * minutes. Pass -Pvisionbridge.enableLocalVlm=false for a fast Kotlin-only
 * iteration or CI lane; the app still builds and runs, and the local engine
 * simply reports itself unavailable when the switch is turned on.
 */
val buildLocalVlm: Boolean =
    (project.findProperty("visionbridge.enableLocalVlm") as String?)?.toBoolean() ?: true

android {
    namespace = "com.abdullah.visionbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdullah.visionbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.13.0"

        // Xiaomi 14T uses a 64-bit ARM processor. Keeping only arm64-v8a removes native binaries
        // for emulators and legacy 32-bit phones. The local VLM raises the stakes: a 32-bit build
        // could not address the model anyway, and CMakeLists.txt refuses to configure for one.
        ndk {
            abiFilters += "arm64-v8a"
        }

        if (buildLocalVlm) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_static",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                    // Belt and braces: Gradle also refuses to hand CMake another ABI.
                    abiFilters += "arm64-v8a"

                    // Zero bloat, and the difference between a two-minute and a
                    // twenty-minute build. Enabling LLAMA_BUILD_TOOLS is required to
                    // get libmtmd, but it also defines llama-cli, llama-bench,
                    // llama-tts and a dozen other executables that AGP would
                    // otherwise compile and then discard. Naming the target builds
                    // only it and the static libraries it actually links.
                    targets += "visionbridge_vlm"
                }
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md"
        )
    }

    if (buildLocalVlm) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        // Uncompressed .so pages can be mapped straight from the APK, which keeps
        // the native library out of the app's private storage a second time.
        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
