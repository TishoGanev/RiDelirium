package com.bgdeveloper.ridelirium.push

import android.content.Context
import com.bgdeveloper.ridelirium.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * Firebase is set up from local.properties (FCM_PROJECT_ID, FCM_APP_ID, FCM_API_KEY, FCM_SENDER_ID) instead of a
 * google-services.json, so a build without those values still works and simply has no push.
 */
object PushSetup {
    val isConfigured: Boolean
        get() = BuildConfig.FCM_PROJECT_ID.isNotEmpty() && BuildConfig.FCM_APP_ID.isNotEmpty() &&
            BuildConfig.FCM_API_KEY.isNotEmpty() && BuildConfig.FCM_SENDER_ID.isNotEmpty()

    fun init(context: Context) {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
        // Crash reports ride on the same Firebase app; debug builds stay quiet.
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
    }

    /** This install's FCM token, or null when push is off or Google services are unavailable. */
    suspend fun currentToken(): String? = if (!isConfigured) null else
        withContext(Dispatchers.Main) { runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() }
}
