plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uwrlab.gridcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uwrlab.gridcam"
        minSdk = 29
        targetSdk = 34
        // GitHub build number -> version, so the app can tell when a newer build exists
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = run + 100
        versionName = "1.0.$run"
    }

    // Fixed key so every GitHub build can be installed over the previous one
    signingConfigs {
        create("gridcam") {
            storeFile = file("gridcam-debug.jks")
            storePassword = "gridcam123"
            keyAlias = "gridcam"
            keyPassword = "gridcam123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gridcam")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gridcam")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop")
    }
}

dependencies {
    val camerax = "1.5.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
