import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "code.ai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "code.ai.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 35
        versionName = "3.35.5"

        val defaultClientToken = "coaide_lock_987654"
        val clientToken = (project.findProperty("APP_CLIENT_TOKEN") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: defaultClientToken

        buildConfigField("String", "WORKER_URL", "\"https://coaide-api.storeplayapp9123.workers.dev\"")
        buildConfigField("String", "APP_CLIENT_TOKEN", "\"$clientToken\"")
    }

    val releaseStorePath = (project.findProperty("RELEASE_STORE_FILE") as? String)
        ?.takeIf { it.isNotBlank() }
    val releaseStorePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as? String).orEmpty()
    val releaseKeyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as? String).orEmpty()
    val releaseKeyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as? String).orEmpty()

    signingConfigs {
        create("release") {
            if (releaseStorePath != null && releaseStorePassword.isNotBlank() && releaseKeyAlias.isNotBlank()) {
                val candidateFile = File(releaseStorePath)
                storeFile = if (candidateFile.isAbsolute) candidateFile else rootProject.file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = if (releaseKeyPassword.isNotBlank()) releaseKeyPassword else releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            }
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
