package com.bgdeveloper.ridelirium.push

import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives the server's data messages (see Server/api/push.php) and shows them as notifications. */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var friends: FriendsRepository

    override fun onNewToken(token: String) {
        friends.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        CrewNotifications.show(applicationContext, message.data)
        // Someone joined or left: show the new crew without waiting for the next poll.
        CoroutineScope(Dispatchers.Main.immediate).launch { friends.refresh() }
    }
}
