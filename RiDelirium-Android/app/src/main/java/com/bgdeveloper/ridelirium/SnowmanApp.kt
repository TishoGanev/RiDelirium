package com.bgdeveloper.ridelirium

import android.app.Application
import com.bgdeveloper.ridelirium.push.PushSetup
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SnowmanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PushSetup.init(this)
    }
}
