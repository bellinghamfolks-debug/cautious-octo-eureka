plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Downloads the four PP-OCR ONNX models into `src/main/assets/ppocr` before anything is packaged.
 *
 * The models are not committed: ~26 MB of binaries would live in every clone forever, and a pinned
 * URL plus a pinned SHA-256 is a stronger guarantee of what is in the APK than a file someone
 * committed once. The checksum decides whether a download is accepted, so a mirror that serves the
 * wrong bytes fails the build instead of shipping a reader that quietly produces nonsense.
 */
val fetchOcrModels by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Fetches and checksums the bundled PP-OCR models"
    workingDir = rootDir
    commandLine("python3", "scripts/fetch_ocr_models.py")
    // Re-runs only when the pinned set changes or an asset goes missing.
    inputs.file(rootProject.file("scripts/fetch_ocr_models.py"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ppocr"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchOcrModels) }

android {
    namespace = "com.abdullah.visionbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdullah.visionbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "2.1.1"

        // 64-bit only. ONNX Runtime's AAR ships four ABIs; abiFilters keeps arm64-v8a and drops
        // the rest at packaging time, so the APK carries no library this device cannot run.
        ndk {
            abiFilters += "arm64-v8a"
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
        // Uncompressed .so pages map straight from the APK instead of being extracted again.
        jniLibs.useLegacyPackaging = false
        // The models are already compact; deflating them only costs time on every cold start.
    }

    androidResources {
        // The models are already compact; deflating them only costs time on every cold start.
        noCompress += "onnx"
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
    // On-device OCR. The AAR carries a complete Java API, so PP-OCRv5 runs with no JNI bridge
    // and no CMake in this project at all.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
