import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.cpapmaskguard"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.cpapmaskguard"
        minSdk = 26
        targetSdk = 37
        versionCode = 22
        versionName = "0.4.2"

        val localProps = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { localProps.load(it) }
        }
        val rawKey = localProps.getProperty("minimax.api.key") ?: ""
        val rawEndpoint = localProps.getProperty("minimax.endpoint") ?: ""
        // Escape for embedding into a Java string literal in BuildConfig
        fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "MINIMAX_API_KEY", "\"${escape(rawKey)}\"")
        buildConfigField("String", "MINIMAX_ENDPOINT", "\"${escape(rawEndpoint)}\"")
        val rawRelay = localProps.getProperty("cpap.relay.base") ?: ""
        // Empty -> official https://cpap.njxxzf.cn; cpap.relay.base=off disables relay
        buildConfigField("String", "CPAP_RELAY_BASE", "\"${escape(rawRelay)}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":detector-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")

    // On-device MobileNet embedding
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
