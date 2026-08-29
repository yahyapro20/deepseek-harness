package com.yahyapro20.dshmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HarnessService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installerJob: Job? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "harness_service"
        private const val NOTIFICATION_ID = 1000
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        installerJob = serviceScope.launch {
            try {
                val installer = BootstrapInstaller(this@HarnessService)
                installer.install()
                
                // Start web server after bootstrap
                if (ProotRunner.isProotReady(this@HarnessService) &&
                    ProotRunner.isRootfsReady(this@HarnessService) &&
                    ProotRunner.isNodeReady(this@HarnessService)) {
                    
                    updateNotification("DeepSeek Harness is running")
                    ProotRunner.startWebServer(this@HarnessService)
                }
            } catch (e: Exception) {
                HarnessState.update(
                    Phase.ERROR,
                    "Service failed: ${e.message}",
                    errorDetail = e.stackTraceToString(),
                    canRetry = true
                )
                updateNotification("Error: ${e.message}")
            }
        }
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "DSH Harness Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DeepSeek Harness background service"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("DSH Mobile")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        installerJob?.cancel()
        serviceScope.cancel()
    }
}
