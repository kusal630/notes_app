plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

import java.io.File
import java.net.URI
import java.util.zip.ZipFile

android {
    namespace = "com.premiumnotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.premiumnotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // On-device, offline speech-to-text for Classroom Notes (Apache-2.0, F-Droid friendly).
    implementation(libs.vosk.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.tooling)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Downloads the small English Vosk model once and unpacks it into src/main/assets/vosk/model
 * so Classroom Notes works fully offline from first launch (F-Droid friendly: no mandatory
 * runtime download). ~40 MB. Run: ./gradlew downloadVoskModel
 * The model is not committed to git; builds without it still succeed — the app shows a
 * "speech model not installed" message and disables recording gracefully.
 */
val VOSK_MODEL_ID = "vosk-model-small-en-us-0.15"
val voskModelDir = layout.projectDirectory.dir("src/main/assets/vosk/model")

tasks.register("downloadVoskModel") {
    group = "build"
    description = "Download and unpack the small English Vosk speech model into assets"
    val marker = voskModelDir.file("am")
    val zip = layout.buildDirectory.file("vosk-model.zip")
    inputs.property("modelId", VOSK_MODEL_ID)
    outputs.file(marker)
    doLast {
        if (marker.asFile.exists()) {
            logger.lifecycle("Vosk model already present: $voskModelDir")
            return@doLast
        }
        val url = URI("https://alphacephei.com/vosk/models/$VOSK_MODEL_ID.zip").toURL()
        logger.lifecycle("Downloading $VOSK_MODEL_ID.zip (~40 MB) ...")
        url.openStream().use { input -> zip.get().asFile.outputStream().use { input.copyTo(it) } }
        val outDir = voskModelDir.asFile
        outDir.mkdirs()
        ZipFile(zip.get().asFile).use { zf ->
            for (entry in zf.entries()) {
                val name = entry.name.substringAfter("$VOSK_MODEL_ID/")
                if (name.isBlank() || entry.isDirectory) continue
                val target = File(outDir, name)
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { it.copyTo(target.outputStream()) }
            }
        }
        logger.lifecycle("Vosk model unpacked to $voskModelDir")
    }
}