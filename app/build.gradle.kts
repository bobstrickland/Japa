import java.util.Properties
import java.io.FileInputStream

// 1. Function to read the current version code
fun getVersionCode(): Int {
    val versionPropsFile = file("version.properties")
    if (versionPropsFile.exists()) {
        val versionProps = Properties()
        versionProps.load(FileInputStream(versionPropsFile))
        return versionProps.getProperty("VERSION_CODE").toInt()
    } else {
        println("version.properties does not exist")
    }
    return 1 // Default if file doesn't exist
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.strickland.japa"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.strickland.japa"
        minSdk = 26
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = "0.0.$versionCode"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
