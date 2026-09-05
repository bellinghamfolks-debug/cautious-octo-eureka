plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val applyUltraLiveLatencyPatch by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Applies the backpressured low-latency Gemini Live capture path"
    workingDir = rootDir
    commandLine("python3", "scripts/apply_ultra_live_latency_patch.py")
    inputs.file(rootProject.file("scripts/apply_ultra_live_latency_patch.py"))
}

val finalizeLiveBackpressure by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Hardens Live turn gating and mode feedback after the main patch"
    workingDir = rootDir
    commandLine("python3", "scripts/finalize_live_backpressure.py")
    inputs.file(rootProject.file("scripts/finalize_live_backpressure.py"))
    dependsOn(applyUltraLiveLatencyPatch)
}

val applyLiveTextFemaleVoicePatch by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Adds local female-first TTS and Live transcript result handling"
    workingDir = rootDir
    commandLine("python3", "scripts/apply_live_text_female_voice_patch.py")
    inputs.file(rootProject.file("scripts/apply_live_text_female_voice_patch.py"))
    dependsOn(finalizeLiveBackpressure)
}

val applyLiveRequiredAudioTranscriptPatch by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Requires real Gemini Live using AUDIO plus output transcription, with no cloud fallback"
    workingDir = rootDir
    commandLine("python3", "scripts/apply_live_required_audio_transcript_patch.py")
    inputs.file(rootProject.file("scripts/apply_live_required_audio_transcript_patch.py"))
    dependsOn(applyLiveTextFemaleVoicePatch)
}

val applyLiveAccuracyGuardV362 by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Raises Live OCR fidelity, resets text context, and prevents crop-driven hallucinations"
    workingDir = rootDir
    commandLine("python3", "scripts/apply_live_accuracy_guard_v362.py")
    inputs.file(rootProject.file("scripts/apply_live_accuracy_guard_v362.py"))
    dependsOn(applyLiveRequiredAudioTranscriptPatch)
}

val applyEsightViewportSettingsV370 by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Applies the calibrated eSight viewport and verified settings audit"
    workingDir = rootDir
    commandLine("python3", "scripts/apply_esight_viewport_settings_v370.py")
    inputs.file(rootProject.file("scripts/apply_esight_viewport_settings_v370.py"))
    dependsOn(applyLiveAccuracyGuardV362)
}

val fetchOcrModels by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Fetches and checksums the bundled PP-OCR models"
    workingDir = rootDir
    commandLine("python3", "scripts/fetch_ocr_models.py")
    inputs.file(rootProject.file("scripts/fetch_ocr_models.py"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ppocr"))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(applyEsightViewportSettingsV370, fetchOcrModels)
}

android {
    namespace = "com.abdullah.visionbridge"
    compileSdk = 36

    val stableSigningPassword = System.getenv("VISIONBRIDGE_KEYSTORE_PASSWORD")
    val stableSigningFile = rootProject.file("signing/visionbridge-signing-v1.jks")

    signingConfigs {
        if (!stableSigningPassword.isNullOrBlank() && stableSigningFile.exists()) {
            create("visionbridgeStable") {
                storeFile = stableSigningFile
                storePassword = stableSigningPassword
                keyAlias = "visionbridge"
                keyPassword = stableSigningPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.abdullah.visionbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "3.7.0"

        ndk {
            abiFilters += "arm64-v8a"
            if (project.hasProperty("visionbridge.emulatorAbi")) {
                abiFilters += "x86"
                abiFilters += "x86_64"
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            signingConfigs.findByName("visionbridgeStable")?.let { signingConfig = it }
        }
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
        jniLibs.useLegacyPackaging = false
    }

    androidResources { noCompress += "onnx" }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api30") {
                    device = "Pixel 6"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
            }
        }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
