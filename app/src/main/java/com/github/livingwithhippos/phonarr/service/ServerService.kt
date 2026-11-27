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
import com.github.livingwithhippos.phonarr.core.data.listenHost
import com.github.livingwithhippos.phonarr.core.util.lanAddresses
import com.github.livingwithhippos.phonarr.core.util.serverAddresses
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the Torznab server alive while the user wants it running. */
class ServerService : Service() {
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        val container = (application as PhonarrApp).container
        val settings = container.settings.settings.value
        val port = settings.port
        val host = settings.listenHost
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(port, host),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        try {
            container.server.start(port, host)
            _running.value = true
            if (host == "0.0.0.0") watchNetworks(port, host) else unwatchNetworks()
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Unable to start server on $host:$port: ${e.message}"
            stopServer()
        }
        return START_STICKY
    }

    /** Keeps the notification's address current while Wi-Fi and other networks come and go. */
    private fun watchNetworks(port: Int, host: String) {
        unwatchNetworks()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val refresh = refresh@{
            // a callback already queued when the server stopped must not bring the notification back
            if (networkCallback == null || !_running.value) return@refresh
            val before = lastText
            val n = buildNotification(port, host)
            if (lastText != before) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
        }
        // without INTERNET, so a Wi-Fi with no internet access still counts
        val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unwatchNetworks() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun stopServer() {
        unwatchNetworks()
        (application as PhonarrApp).container.server.stop()
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unwatchNetworks()
        (application as PhonarrApp).container.server.stop()
        _running.value = false
        super.onDestroy()
    }

    /** Builds the notification and remembers its text in [lastText]. */
    private fun buildNotification(port: Int, host: String): Notification {
        val addresses = serverAddresses(host, port, if (host == "0.0.0.0") lanAddresses() else emptyList())
        val text = when {
            addresses.noNetwork -> getString(R.string.notification_no_network, addresses.primary)
            addresses.all.size > 1 -> getString(
                R.string.notification_more,
                getString(R.string.notification_text, addresses.primary),
                addresses.all.size - 1,
            )
            else -> getString(R.string.notification_text, addresses.primary)
        }
        lastText = text
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
            .setContentText(text)
            .apply {
                if (addresses.all.size > 1) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_text, addresses.all.joinToString(", "))))
                }
            }
            .setOnlyAlertOnce(true)
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
