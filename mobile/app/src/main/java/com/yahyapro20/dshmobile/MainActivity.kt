package com.yahyapro20.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private var webViewLoaded = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

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
        startService(Intent(this, HarnessService::class.java))
        observeStatus()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                            statusText.text = buildString {
                                append(status.message)
                                if (status.errorDetail != null) {
                                    append("\n\n")
                                    append(status.errorDetail)
                                }
                            }
                        }
                        else -> {
                            if (!webViewLoaded) {
                                statusText.visibility = View.VISIBLE
                                webView.visibility = View.GONE
                                statusText.text = status.message
                            }
                        }
                    }
                }
            }
        }
    }
}
