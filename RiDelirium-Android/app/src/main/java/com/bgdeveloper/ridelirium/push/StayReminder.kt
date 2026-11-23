package com.bgdeveloper.ridelirium.push

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bgdeveloper.ridelirium.MainActivity
import com.bgdeveloper.ridelirium.R
import com.bgdeveloper.ridelirium.friends.Crew
import com.bgdeveloper.ridelirium.friends.FriendsRepository
import com.bgdeveloper.ridelirium.friends.Stay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * "Sharing with ANNA'S CREW ends at 13:45": a notification 10 minutes before a timed stay in a crew ends, with
 * +1 HOUR to stay longer. Tapping it opens Ride Together, where CHANGE TIME picks any end. Kept in step with the
 * crews by [FriendsRepository]; a stay "until I leave" gets none. The alarm is inexact (no exact-alarm
 * permission), so while the phone sleeps it can come a few minutes late.
 */
object StayReminder {
    private const val LEAD_MS = 10 * 60_000L
    private const val PREFS = "stayReminders"
    private const val KEY_IDS = "crewIds"
    internal const val ACTION_SHOW = "com.bgdeveloper.ridelirium.STAY_SHOW"
    internal const val ACTION_EXTEND = "com.bgdeveloper.ridelirium.STAY_EXTEND"
    internal const val EXTRA_CREW = "crewId"
    internal const val EXTRA_NAME = "crewName"
    internal const val EXTRA_ENDS = "endsAtMs"
    /** On the MainActivity intent: open the Ride Together sheet. */
    const val EXTRA_OPEN_RIDE_TOGETHER = "openRideTogether"

    /** One alarm per crew whose end is more than 10 minutes away; any other crew's alarm and shown reminder go. */
    fun sync(context: Context, crews: List<Crew>, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val shown = NotificationManagerCompat.from(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wanted = crews.filter { (it.myExpiresAtMs ?: 0L) - LEAD_MS > nowMs }
        val keep = wanted.map { it.id }.toSet()
        (prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() - keep).forEach { id ->
            alarms.cancel(showIntent(context, id, "", 0L))
            shown.cancel(notificationId(id))
        }
        wanted.forEach { crew ->
            val end = crew.myExpiresAtMs ?: return@forEach
            // Same request code and action: replaces the alarm this crew had.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end - LEAD_MS, showIntent(context, crew.id, crew.name, end))
            // A reminder on screen for an end that has moved is out of date.
            shown.cancel(notificationId(crew.id))
        }
        prefs.edit().putStringSet(KEY_IDS, keep).apply()
    }

    internal fun notificationId(crewId: String): Int = "stay-$crewId".hashCode()

    internal fun show(context: Context, crewId: String, crewName: String, endsAtMs: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CrewNotifications.CHANNEL, "Ride Together", NotificationManager.IMPORTANCE_HIGH))
        val end = Instant.ofEpochMilli(endsAtMs).atZone(ZoneId.systemDefault())
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_RIDE_TOGETHER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val extend = Intent(context, StayReceiver::class.java)
            .setAction(ACTION_EXTEND)
            .putExtra(EXTRA_CREW, crewId)
            .putExtra(EXTRA_ENDS, endsAtMs)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val notification = NotificationCompat.Builder(context, CrewNotifications.CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Sharing with ${crewName.uppercase()} ends at ${Stay.clock(end)}.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(context, notificationId(crewId), open, flags))
            .addAction(0, "+1 HOUR", PendingIntent.getBroadcast(context, notificationId(crewId), extend, flags))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(crewId), notification)
    }

    private fun showIntent(context: Context, crewId: String, crewName: String, endsAtMs: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, crewId.hashCode(),
            Intent(context, StayReceiver::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_CREW, crewId)
                .putExtra(EXTRA_NAME, crewName)
                .putExtra(EXTRA_ENDS, endsAtMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** The reminder's alarm (shows it) and its +1 HOUR button (stays an hour longer without opening the app). */
@AndroidEntryPoint
class StayReceiver : BroadcastReceiver() {
    @Inject lateinit var friends: FriendsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val crewId = intent.getStringExtra(StayReminder.EXTRA_CREW) ?: return
        val endsAtMs = intent.getLongExtra(StayReminder.EXTRA_ENDS, 0L)
        when (intent.action) {
            StayReminder.ACTION_SHOW ->
                StayReminder.show(context, crewId, intent.getStringExtra(StayReminder.EXTRA_NAME).orEmpty(), endsAtMs)
            StayReminder.ACTION_EXTEND -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        friends.setStay(crewId, Instant.ofEpochMilli(endsAtMs + 3_600_000L).atZone(ZoneId.systemDefault()))
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
