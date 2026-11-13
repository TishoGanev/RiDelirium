package com.bgdeveloper.ridelirium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.bgdeveloper.ridelirium.friends.InviteLink
import com.bgdeveloper.ridelirium.ui.AppRoot
import com.bgdeveloper.ridelirium.ui.theme.SnowmanTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var friends: FriendsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnowmanTheme {
                AppRoot()
            }
        }
        if (savedInstanceState == null) handleInvite(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** An invite link (https://<api host>/join/ABCD2345 or snowboarder://join/ABCD2345) from a QR scan or a message opens Ride Together and joins. */
    private fun handleInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (InviteLink.isInvite(uri.scheme, uri.host, uri.path)) friends.handleLink(uri.toString())
    }
}
