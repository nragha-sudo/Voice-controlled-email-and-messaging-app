package com.voiceaccess.messenger.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voiceaccess.messenger.R
import com.voiceaccess.messenger.controller.MessageReadSync
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.ui.MainActivity

/**
 * Keeps [LocalApiServer] alive as a foreground service so the API stays
 * reachable over Tailscale while the app is backgrounded or the screen is
 * off — a server started only from an Activity would be killed by the OS
 * the moment the user leaves the app.
 *
 * Started/stopped from MainActivity's "Start Server"/"Stop Server" buttons.
 * The persistent notification is required by Android for any foreground
 * service and doubles as an at-a-glance "yes, it's running" indicator.
 */
class ApiServerService : Service() {

    private var server: LocalApiServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())

        if (server == null) {
            val repository = MessageRepository.getInstance(applicationContext)
            val readSync = MessageReadSync(applicationContext)
            val apiKeyStore = ApiKeyStore(applicationContext)
            val newServer = LocalApiServer(PORT, applicationContext, repository, readSync, apiKeyStore)
            try {
                newServer.start(NANOHTTPD_SOCKET_TIMEOUT_MS, false)
                server = newServer
                Log.i(TAG, "onStartCommand: API server listening on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "onStartCommand: failed to start API server on port $PORT", e)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        Log.i(TAG, "onDestroy: API server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.api_server_notification_title))
            .setContentText(getString(R.string.api_server_notification_text, PORT))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.api_server_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    companion object {
        const val PORT = 8765

        /** Read by MainActivity to reflect the toggle button/status text — accurate for this process only, which is fine since a process restart also loses the running service. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NANOHTTPD_SOCKET_TIMEOUT_MS = 5_000
        private const val TAG = "VAM-ApiServerService"
        private const val CHANNEL_ID = "api_server"
        private const val NOTIFICATION_ID = 42
    }
}
