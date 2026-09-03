import java.util.Properties
import java.io.FileInputStream

// 1. Function to read the current version code
fun getVersionCode(): Int {
    val versionPropsFile = file("version.properties")
    if (versionPropsFile.exists()) {
        val versionProps = Properties()
        versionProps.load(FileInputStream(versionPropsFile))
        val nextCode = versionProps.getProperty("VERSION_CODE").toInt()
        println("Version code set to $nextCode")
        return nextCode
    } else {
        println("version.properties does not exist")
    }
    return 1 // Default if file doesn't exist
}

fun getVersionName(): String {
    val versionPropsFile = file("version.properties")
    if (versionPropsFile.exists()) {
        val versionProps = Properties()
        versionProps.load(FileInputStream(versionPropsFile))
        val nextCode = versionProps.getProperty("VERSION_CODE")
        val versionName = versionProps.getProperty("VERSION_NAME")+nextCode
        println("Version Name set to $versionName")
        return versionName
    } else {
        println("version.properties does not exist")
    }
    return "0." // Default if file doesn't exist
}

plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.strickland.japa"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.strickland.japa"
        minSdk = 26
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ksp {
        // Exported schemas give migrations an exact reference and let Room verify them.
        arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.play.app.update)
    // QR: zxing generates the code, the Play Services scanner supplies the camera UI.
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Material pulls in RecyclerView 1.1.0 (2019); 1.3.x has bindingAdapterPosition.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath; the stubbed android.jar one throws.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
}
