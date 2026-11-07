plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.penbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.penbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // One shared development key, so new builds install over old ones without
    // uninstalling. Make your own key before publishing the app anywhere.
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/penbridge-dev.jks")
            storePassword = "penbridge"
            keyAlias = "penbridge"
            keyPassword = "penbridge"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
