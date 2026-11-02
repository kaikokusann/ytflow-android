plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ytchatplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ytchatplayer.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        ignoreAssetsPattern = ""
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.mozilla.geckoview:geckoview:151.0.20260608154138")
}
