package com.github.livingwithhippos.phonarr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.github.livingwithhippos.phonarr.MainActivity
import com.github.livingwithhippos.phonarr.PhonarrApp
import com.github.livingwithhippos.phonarr.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the Torznab server alive while the user wants it running. */
class ServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        val container = (application as PhonarrApp).container
        val port = container.settings.settings.value.port
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(port),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        try {
            container.server.start(port)
            _running.value = true
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Unable to start server on port $port: ${e.message}"
            stopServer()
        }
        return START_STICKY
    }

    private fun stopServer() {
        (application as PhonarrApp).container.server.stop()
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        (application as PhonarrApp).container.server.stop()
        _running.value = false
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.github.livingwithhippos.phonarr.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ServerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
