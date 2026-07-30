plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val arabicTessdataUrl =
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/ara.traineddata"
val arabicTessdataFile = layout.projectDirectory.file("src/main/assets/tessdata/ara.traineddata")
val arabicTessdataTemporary = layout.buildDirectory.file("downloads/ara.traineddata")
val prepareArabicTessdata by tasks.registering(Exec::class) {
    val destination = arabicTessdataFile.asFile.absolutePath
    val temporary = arabicTessdataTemporary.get().asFile.absolutePath
    outputs.file(arabicTessdataFile)
    commandLine(
        "bash",
        "-c",
        """
        set -euo pipefail
        destination='${destination.replace("'", "'\\''")}'
        temporary='${temporary.replace("'", "'\\''")}'
        if [ -f "${'$'}destination" ]; then
          bytes=${'$'}(wc -c < "${'$'}destination")
          if [ "${'$'}bytes" -ge 1000000 ] && [ "${'$'}bytes" -le 3000000 ]; then
            exit 0
          fi
        fi
        mkdir -p "${'$'}(dirname "${'$'}destination")" "${'$'}(dirname "${'$'}temporary")"
        rm -f "${'$'}temporary"
        curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
          '${arabicTessdataUrl}' --output "${'$'}temporary"
        bytes=${'$'}(wc -c < "${'$'}temporary")
        if [ "${'$'}bytes" -lt 1000000 ] || [ "${'$'}bytes" -gt 3000000 ]; then
          echo "Arabic tessdata download is incomplete: ${'$'}bytes bytes" >&2
          exit 1
        fi
        mv "${'$'}temporary" "${'$'}destination"
        """.trimIndent(),
    )
}

android {
    namespace = "com.abdullah.visionbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdullah.visionbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.10.0"

        // Xiaomi 14T uses a 64-bit ARM processor. Keeping only arm64-v8a removes native ML Kit
        // and Tesseract binaries for emulators and legacy 32-bit phones.
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

// Both APK assembly and lint model generation inspect the main assets directory.
// Explicit dependencies keep Gradle 8 configuration-cache validation deterministic.
tasks.configureEach {
    if (
        name == "preBuild" ||
        name.contains("Assets", ignoreCase = false) ||
        name.contains("Lint", ignoreCase = false)
    ) {
        dependsOn(prepareArabicTessdata)
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
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
