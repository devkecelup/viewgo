plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.viewgo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.viewgo"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
