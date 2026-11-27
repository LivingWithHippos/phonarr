package com.github.livingwithhippos.phonarr.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.livingwithhippos.phonarr.R
import java.net.URI

/** Tells the user a site needs a manual check, at most once every few minutes per site. */
class ChallengeNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val lastShown = HashMap<String, Long>()

    fun notify(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        synchronized(lastShown) {
            val now = System.currentTimeMillis()
            if (now - (lastShown[host] ?: 0) < THROTTLE_MS) return
            lastShown[host] = now
        }
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.challenge_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ),
        )
        val origin =
            runCatching { URI(url).let { "${it.scheme}://${it.authority}/" } }.getOrDefault(url)
        val open = PendingIntent.getActivity(
            appContext, host.hashCode(), ChallengeActivity.intent(appContext, origin),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.challenge_title, host))
            .setContentText(appContext.getString(R.string.challenge_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(appContext).notify(host.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "challenges"
        private const val THROTTLE_MS = 10 * 60_000L
    }
}
