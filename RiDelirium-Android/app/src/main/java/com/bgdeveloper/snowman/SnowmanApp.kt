package com.bgdeveloper.snowman

import android.app.Application
import com.bgdeveloper.snowman.push.PushSetup
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SnowmanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PushSetup.init(this)
    }
}
