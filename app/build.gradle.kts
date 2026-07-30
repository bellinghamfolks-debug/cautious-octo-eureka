import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val arabicTessdataUrl =
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/ara.traineddata"
val generatedTessdataAssets = layout.buildDirectory.dir("generated/tessdataAssets")
val prepareArabicTessdata by tasks.registering {
    val destination = generatedTessdataAssets.map { it.file("tessdata/ara.traineddata") }
    inputs.property("arabicTessdataUrl", arabicTessdataUrl)
    outputs.file(destination)

    doLast {
        val target = destination.get().asFile
        if (target.isFile && target.length() in 1_000_000L..3_000_000L) return@doLast

        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.download")
        temporary.delete()
        URI(arabicTessdataUrl).toURL().openStream().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(temporary.length() in 1_000_000L..3_000_000L) {
            "Arabic tessdata download is incomplete: ${temporary.length()} bytes"
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
}

android {
    namespace = "com.abdullah.visionbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdullah.visionbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.9.0"

        // Xiaomi 14T uses a 64-bit ARM processor. Keeping only arm64-v8a removes native ML Kit
        // and Tesseract binaries for emulators and legacy 32-bit phones.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    sourceSets.getByName("main").assets.srcDir(generatedTessdataAssets)

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

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("Assets")
}.configureEach {
    dependsOn(prepareArabicTessdata)
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
