package com.bgdeveloper.ridelirium.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bgdeveloper.ridelirium.MainActivity
import com.bgdeveloper.ridelirium.R

/** Builds the alerts the server sends: crew joined/left, wait for me, invites. */
object CrewNotifications {
    private const val CHANNEL = "crew"
    private const val EXTRA_WAIT = "waitId"

    fun show(context: Context, data: Map<String, String>) {
        val body = data["body"] ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Ride Together", NotificationManager.IMPORTANCE_HIGH))

        val event = data["event"]
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Tapping an invite joins that crew, the same as scanning its link.
            if (event == "invite") data["code"]?.let { this.data = Uri.parse("ridelirium://join/$it") }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(data["title"] ?: context.getString(R.string.app_name))
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (event == "wait") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(PendingIntent.getActivity(context, event.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        val waitId = data["waitId"]
        if (event == "wait" && waitId != null) {
            val accept = Intent(context, WaitReceiver::class.java).putExtra(EXTRA_WAIT, waitId)
            builder.addAction(
                0, "OK, WAITING",
                PendingIntent.getBroadcast(context, waitId.hashCode(), accept, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        val id = (waitId ?: data["code"] ?: event ?: body).hashCode()
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun waitId(intent: Intent): String? = intent.getStringExtra(EXTRA_WAIT)
}
