import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.tgwsproxy.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tgwsproxy.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "2.5.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
                ?: "tgwsproxy-release.jks"
            storeFile = rootProject.file(keystorePath)
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
                ?: "tgwsproxy"
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            val hasReleaseKey = releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrBlank()
            if (!hasReleaseKey && System.getenv("RELEASE_REQUIRED") == "true") {
                throw GradleException("RELEASE_REQUIRED=true, but release signing keystore or password is missing")
            }
            signingConfig = if (hasReleaseKey) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Gradle 9.7 is currently only a milestone/snapshot; keep the latest stable wrapper.
        disable += "AndroidGradlePluginVersion"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.jna) {
        artifact { type = "aar" }
    }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
