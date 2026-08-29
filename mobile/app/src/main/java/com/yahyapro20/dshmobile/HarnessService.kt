package com.yahyapro20.dshmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class HarnessService : Service() {

    companion object {
        private const val TAG = "HarnessService"
        private const val CHANNEL_ID = "dsh_harness"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.yahyapro20.dshmobile.action.STOP"
        private const val MAX_RESTARTS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var dshProcess: Process? = null
    private var restartCount = 0
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            dshProcess?.destroy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        scope.launch { runHarness() }
        return START_STICKY
    }

    private suspend fun runHarness() {
        try {
            HarnessState.update(Phase.BOOTSTRAPPING, getString(R.string.status_bootstrapping))
            BootstrapInstaller.ensureInstalled(applicationContext) { msg ->
                HarnessState.update(Phase.BOOTSTRAPPING, msg)
                updateNotification(msg)
            }

            while (!stopping) {
                HarnessState.update(Phase.STARTING_DSH, getString(R.string.status_starting_dsh))
                updateNotification(getString(R.string.status_starting_dsh))

                val process = ProotRunner.startWebServer(applicationContext)
                dshProcess = process

                // Log guest stdout/stderr for debugging; also gives us a live signal
                // that the process is still alive.
                scope.launch {
                    process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "[dsh] $it") }
                }

                val reachable = waitForPort(BootConfig.WEB_PORT, BootConfig.HEALTH_CHECK_TIMEOUT_MS)
                if (reachable) {
                    HarnessState.update(Phase.RUNNING, getString(R.string.notif_running))
                    updateNotification(getString(R.string.notif_running))
                    restartCount = 0
                } else {
                    Log.w(TAG, "dsh web did not open port ${BootConfig.WEB_PORT} in time")
                }

                val exitCode = process.waitFor()
                Log.w(TAG, "dsh web exited with code $exitCode")
                if (stopping) break

                restartCount++
                if (restartCount > MAX_RESTARTS) {
                    HarnessState.update(
                        Phase.ERROR,
                        getString(R.string.status_error),
                        "dsh web exited $restartCount times in a row (exit code $exitCode)"
                    )
                    updateNotification(getString(R.string.status_error))
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Harness failed", e)
            HarnessState.update(Phase.ERROR, getString(R.string.status_error), e.message)
            updateNotification(getString(R.string.status_error))
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                    return true
                }
            } catch (_: Exception) {
                // Not up yet.
            }
            if (dshProcess?.isAlive == false) return false
            Thread.sleep(BootConfig.HEALTH_CHECK_INTERVAL_MS)
        }
        return false
    }

    override fun onDestroy() {
        stopping = true
        dshProcess?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, HarnessService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
