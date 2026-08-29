package com.yahyapro20.dshmobile

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private var webViewLoaded = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = getString(R.string.status_bootstrapping)
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        webView = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        maybeRequestNotificationPermission()
        
        // Start the service which will handle installation
        val serviceIntent = Intent(this, HarnessService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        observeStatus()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HarnessState.status.collect { status ->
                    when (status.phase) {
                        Phase.RUNNING -> {
                            statusText.visibility = View.GONE
                            if (!webViewLoaded) {
                                webViewLoaded = true
                                webView.visibility = View.VISIBLE
                                webView.loadUrl("http://127.0.0.1:${BootConfig.WEB_PORT}")
                            }
                        }
                        Phase.ERROR -> {
                            statusText.visibility = View.VISIBLE
                            webView.visibility = View.GONE
                            
                            val errorMsg = buildString {
                                append(status.message)
                                
                                if (status.canRetry) {
                                    append("\n\n")
                                    append("Failed: ${status.failedFileName}\n")
                                    append("URL: ${status.failedUrl}\n\n")
                                    append("Please check your connection and restart the app.")
                                }
                                
                                status.errorDetail?.let { detail ->
                                    append("\n\n")
                                    append(detail)
                                }
                            }
                            
                            statusText.text = errorMsg
                        }
                        Phase.BOOTSTRAPPING -> {
                            statusText.visibility = View.VISIBLE
                            webView.visibility = View.GONE
                            
                            val progressText = buildString {
                                append(status.message)
                                
                                status.downloadProgress?.let { progress ->
                                    append("\n\n")
                                    append("File: ${progress.fileName}\n")
                                    
                                    if (progress.isResuming) {
                                        append("️ Resuming download...\n")
                                    }
                                    
                                    if (progress.totalBytes > 0) {
                                        val barWidth = 30
                                        val filledWidth = (progress.percentage * barWidth) / 100
                                        val bar = "█".repeat(filledWidth) + "░".repeat(barWidth - filledWidth)
                                        append("[$bar] ${progress.percentage}%\n")
                                        append("${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}\n")
                                        
                                        if (progress.speedBytesPerSecond > 0) {
                                            append("Speed: ${formatBytes(progress.speedBytesPerSecond)}/s\n")
                                            
                                            if (progress.etaSeconds > 0) {
                                                val mins = progress.etaSeconds / 60
                                                val secs = progress.etaSeconds % 60
                                                append("Time remaining: ${mins}m ${secs}s")
                                            }
                                        }
                                    } else {
                                        append("Downloaded: ${formatBytes(progress.bytesDownloaded)}")
                                    }
                                }
                            }
                            
                            statusText.text = progressText
                        }
                        Phase.STARTING_DSH -> {
                            statusText.visibility = View.VISIBLE
                            webView.visibility = View.GONE
                            statusText.text = status.message
                        }
                        Phase.IDLE -> {
                            statusText.visibility = View.GONE
                            webView.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
