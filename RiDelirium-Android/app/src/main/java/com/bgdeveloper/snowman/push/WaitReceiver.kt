package com.bgdeveloper.snowman.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.bgdeveloper.snowman.friends.FriendsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "OK, waiting" on a wait-for-me notification: answers without opening the app. */
@AndroidEntryPoint
class WaitReceiver : BroadcastReceiver() {
    @Inject lateinit var friends: FriendsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = CrewNotifications.waitId(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                friends.acceptWait(id)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            } finally {
                pending.finish()
            }
        }
    }
}
