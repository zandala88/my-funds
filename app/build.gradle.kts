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
        versionCode = 4
        versionName = "0.4.0"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
    sourceSets.getByName("androidTest").assets.srcDir("../core/src/test/resources/ocr")
    packaging { jniLibs { useLegacyPackaging = true } }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64", "x86"); isUniversalApk = true } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(project(":core"))
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
