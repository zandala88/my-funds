import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}
val signingFile = file(System.getProperty("user.home") + "/.local/share/my-funds/signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "com.myfunds.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.myfunds.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.myfunds.android.LocalStoreInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (signingFile.exists()) create("localRelease") {
            storeFile = file(signingValues.getProperty("storeFile"))
            storePassword = signingValues.getProperty("storePassword")
            keyAlias = signingValues.getProperty("keyAlias")
            keyPassword = signingValues.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    lint { abortOnError = true }
}
dependencies { implementation(project(":core")) }
