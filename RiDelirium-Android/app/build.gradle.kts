import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.firebase.crashlytics)
}

// Keys live in the gitignored local.properties: MAPS_API_KEY.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsApiKey = localProps.getProperty("MAPS_API_KEY", "")
// Ride Together API (Server/api), e.g. https://example.com/v1. Leave empty to disable friends.
val apiBaseUrl = localProps.getProperty("API_BASE_URL", "")
// Firebase Cloud Messaging for "joined / left your crew" alerts. All four from the Firebase app settings; leave empty to disable push.
val fcmProjectId = localProps.getProperty("FCM_PROJECT_ID", "")
val fcmAppId = localProps.getProperty("FCM_APP_ID", "")
val fcmApiKey = localProps.getProperty("FCM_API_KEY", "")
val fcmSenderId = localProps.getProperty("FCM_SENDER_ID", "")
// One build number for iOS, Android and the API: BUILD_NUMBER at the repo root (raise it with ./bump_build_number.sh).
val buildNumber = rootProject.file("../BUILD_NUMBER").readText().trim().toInt()
// Play upload key: storeFile, storePassword, keyAlias, keyPassword in the gitignored keystore.properties
// (see keystore.properties.example). Without it release builds are simply unsigned, so debug and CI builds still work.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.bgdeveloper.ridelirium"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bgdeveloper.ridelirium"
        minSdk = 24
        targetSdk = 37
        versionCode = buildNumber
        versionName = "2.0.0"

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        manifestPlaceholders["fileProviderAuthority"] = "com.bgdeveloper.ridelirium.fileprovider"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"$fcmProjectId\"")
        buildConfigField("String", "FCM_APP_ID", "\"$fcmAppId\"")
        buildConfigField("String", "FCM_API_KEY", "\"$fcmApiKey\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"$fcmSenderId\"")
        buildConfigField("String", "FILE_PROVIDER_AUTHORITY", "\"com.bgdeveloper.ridelirium.fileprovider\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time is used in many places and only exists from Android 8.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // On-device Gemini Nano (AICore) for the written ride insights; unsupported phones just keep the numbers.
    implementation(libs.mlkit.genai.prompt)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
