import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Keys live in the gitignored local.properties: MAPS_API_KEY, FACEBOOK_APP_ID.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsApiKey = localProps.getProperty("MAPS_API_KEY", "")
val facebookAppId = localProps.getProperty("FACEBOOK_APP_ID", "")
// Ride Together API (Server/api), e.g. https://example.com/v1. Leave empty to disable friends.
val apiBaseUrl = localProps.getProperty("API_BASE_URL", "")
// Firebase Cloud Messaging for "joined / left your crew" alerts. All four from the Firebase app settings; leave empty to disable push.
val fcmProjectId = localProps.getProperty("FCM_PROJECT_ID", "")
val fcmAppId = localProps.getProperty("FCM_APP_ID", "")
val fcmApiKey = localProps.getProperty("FCM_API_KEY", "")
val fcmSenderId = localProps.getProperty("FCM_SENDER_ID", "")

android {
    namespace = "com.bgdeveloper.snowman"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bgdeveloper.ridelirium"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        manifestPlaceholders["fileProviderAuthority"] = "com.bgdeveloper.ridelirium.fileprovider"
        buildConfigField("String", "FACEBOOK_APP_ID", "\"$facebookAppId\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"$fcmProjectId\"")
        buildConfigField("String", "FCM_APP_ID", "\"$fcmAppId\"")
        buildConfigField("String", "FCM_API_KEY", "\"$fcmApiKey\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"$fcmSenderId\"")
        buildConfigField("String", "FILE_PROVIDER_AUTHORITY", "\"com.bgdeveloper.ridelirium.fileprovider\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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
