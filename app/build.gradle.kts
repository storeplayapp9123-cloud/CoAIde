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
        versionCode = 1
        versionName = "1.0"

        val defaultClientToken = "coaide_lock_987654"
        val clientToken = (project.findProperty("APP_CLIENT_TOKEN") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: defaultClientToken

        buildConfigField("String", "WORKER_URL", "\"https://coaide-api.storeplayapp9123.workers.dev\"")
        buildConfigField("String", "APP_CLIENT_TOKEN", "\"$clientToken\"")
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
