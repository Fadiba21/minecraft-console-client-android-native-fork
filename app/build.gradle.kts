plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keystore stabil (opsional). Dibuat oleh workflow dari secrets, atau dibuat sekali lalu di-cache.
val releaseKeystore = rootProject.file("signing/release.keystore")
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

// Secret GitHub yang tidak diisi menjadi string kosong, bukan null.
fun envOr(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotEmpty() } ?: default

android {
    namespace = "app.mccdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mccdroid"
        minSdk = 26
        // PENTING: targetSdk 28 disengaja. Android 10+ memblokir exec file dari data aplikasi
        // untuk targetSdk >= 29. MCC adalah executable native (.NET), jadi harus bisa di-exec
        // dari folder data aplikasi (pendekatan yang sama dengan Termux).
        targetSdk = 28
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("ci") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = envOr("KEYSTORE_PASSWORD", "mccdroid")
                keyAlias = envOr("KEY_ALIAS", "mccdroid")
                keyPassword = envOr("KEY_PASSWORD", envOr("KEYSTORE_PASSWORD", "mccdroid"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("ci")
            else signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Bundle runtime .NET besar: jangan dikompres ulang oleh aapt (lebih cepat, ukuran sama).
    androidResources {
        noCompress += listOf("zip")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
