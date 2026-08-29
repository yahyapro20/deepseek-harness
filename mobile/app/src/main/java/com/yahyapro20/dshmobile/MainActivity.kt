package com.yahyapro20.dshmobile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var tvProotStatus: TextView
    private lateinit var tvRootfsStatus: TextView
    private lateinit var tvNodeStatus: TextView
    private lateinit var btnSelectProot: MaterialButton
    private lateinit var btnSelectRootfs: MaterialButton
    private lateinit var btnSelectNode: MaterialButton
    private lateinit var btnDownloadProot: MaterialButton
    private lateinit var btnDownloadRootfs: MaterialButton
    private lateinit var btnDownloadNode: MaterialButton
    private lateinit var btnStartInstall: MaterialButton
    private lateinit var btnDownloadAll: MaterialButton
    private lateinit var cardProgress: MaterialCardView
    private lateinit var tvProgressTitle: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgressDetails: TextView
    private lateinit var webView: WebView

    // State
    private val selectedFiles = mutableMapOf<String, Uri>()
    private val downloadQueued = mutableSetOf<String>()
    private var webViewLoaded = false

    // File Pickers
    private val prootFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it, "proot") }
    }

    private val rootfsFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it, "rootfs") }
    }

    private val nodeFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it, "node") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        checkExistingFiles()
    }

    private fun initViews() {
        tvProotStatus = findViewById(R.id.tvProotStatus)
        tvRootfsStatus = findViewById(R.id.tvRootfsStatus)
        tvNodeStatus = findViewById(R.id.tvNodeStatus)
        btnSelectProot = findViewById(R.id.btnSelectProot)
        btnSelectRootfs = findViewById(R.id.btnSelectRootfs)
        btnSelectNode = findViewById(R.id.btnSelectNode)
        btnDownloadProot = findViewById(R.id.btnDownloadProot)
        btnDownloadRootfs = findViewById(R.id.btnDownloadRootfs)
        btnDownloadNode = findViewById(R.id.btnDownloadNode)
        btnStartInstall = findViewById(R.id.btnStartInstall)
        btnDownloadAll = findViewById(R.id.btnDownloadAll)
        cardProgress = findViewById(R.id.cardProgress)
        tvProgressTitle = findViewById(R.id.tvProgressTitle)
        progressBar = findViewById(R.id.progressBar)
        tvProgressDetails = findViewById(R.id.tvProgressDetails)
        webView = findViewById(R.id.webView)
    }

    private fun setupClickListeners() {
        btnSelectProot.setOnClickListener { prootFileLauncher.launch("*/*") }
        btnSelectRootfs.setOnClickListener { rootfsFileLauncher.launch("*/*") }
        btnSelectNode.setOnClickListener { nodeFileLauncher.launch("*/*") }

        btnDownloadProot.setOnClickListener { queueDownload("proot", BootConfig.PROOT_URL) }
        btnDownloadRootfs.setOnClickListener { queueDownload("rootfs", BootConfig.ROOTFS_URL) }
        btnDownloadNode.setOnClickListener { queueDownload("node", BootConfig.NODE_URL) }

        btnDownloadAll.setOnClickListener {
            if (!selectedFiles.containsKey("proot")) queueDownload("proot", BootConfig.PROOT_URL)
            if (!selectedFiles.containsKey("rootfs")) queueDownload("rootfs", BootConfig.ROOTFS_URL)
            if (!selectedFiles.containsKey("node")) queueDownload("node", BootConfig.NODE_URL)
            updateUI()
        }

        btnStartInstall.setOnClickListener { startInstallation() }
    }

    private fun checkExistingFiles() {
        val externalDir = getExternalFilesDir(null) ?: return
        val cacheDir = File(externalDir, BootConfig.LOCAL_CACHE_SUBDIR)
        
        if (File(cacheDir, BootConfig.LOCAL_PROOT_FILENAME).exists()) {
            selectedFiles["proot"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_PROOT_FILENAME))
        }
        if (File(cacheDir, BootConfig.LOCAL_ROOTFS_FILENAME).exists()) {
            selectedFiles["rootfs"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_ROOTFS_FILENAME))
        }
        if (File(cacheDir, BootConfig.LOCAL_NODE_FILENAME).exists()) {
            selectedFiles["node"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_NODE_FILENAME))
        }
        
        updateUI()
    }

    private fun handleFileSelection(uri: Uri, fileType: String) {
        selectedFiles[fileType] = uri
        downloadQueued.remove(fileType)
        updateUI()
    }

    private fun queueDownload(fileType: String, url: String) {
        downloadQueued.add(fileType)
        selectedFiles.remove(fileType)
        updateUI()
    }

    private fun updateUI() {
        // Update Proot
        when {
            selectedFiles.containsKey("proot") -> {
                tvProotStatus.text = getString(R.string.status_selected)
                tvProotStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            downloadQueued.contains("proot") -> {
                tvProotStatus.text = getString(R.string.status_downloading)
                tvProotStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                tvProotStatus.text = getString(R.string.status_not_selected)
                tvProotStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }

        // Update RootFS
        when {
            selectedFiles.containsKey("rootfs") -> {
                tvRootfsStatus.text = getString(R.string.status_selected)
                tvRootfsStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            downloadQueued.contains("rootfs") -> {
                tvRootfsStatus.text = getString(R.string.status_downloading)
                tvRootfsStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                tvRootfsStatus.text = getString(R.string.status_not_selected)
                tvRootfsStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }

        // Update Node
        when {
            selectedFiles.containsKey("node") -> {
                tvNodeStatus.text = getString(R.string.status_selected)
                tvNodeStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            downloadQueued.contains("node") -> {
                tvNodeStatus.text = getString(R.string.status_downloading)
                tvNodeStatus.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                tvNodeStatus.text = getString(R.string.status_not_selected)
                tvNodeStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }

        // Enable start button if all files are ready
        val allReady = (selectedFiles.containsKey("proot") || downloadQueued.contains("proot")) &&
                      (selectedFiles.containsKey("rootfs") || downloadQueued.contains("rootfs")) &&
                      (selectedFiles.containsKey("node") || downloadQueued.contains("node"))
        btnStartInstall.isEnabled = allReady
    }

        private fun startInstallation() {
        // Hide setup UI safely
        val setupLayout = findViewById<View>(R.id.setupLayout)
        setupLayout.visibility = View.GONE
        
        cardProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Copy selected files to internal storage
                selectedFiles.forEach { (type, uri) ->
                    val destFile = when (type) {
                        "proot" -> File(cacheDir, "proot")
                        "rootfs" -> File(cacheDir, "rootfs-manual.tar.xz")
                        "node" -> File(cacheDir, "node-manual.tar.gz")
                        else -> null
                    }
                    
                    destFile?.let { file ->
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (type == "proot") {
                            file.setExecutable(true)
                        }
                    }
                }

                // Start service
                val serviceIntent = Intent(this@MainActivity, HarnessService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // Observe progress
                observeProgress()
            } catch (e: Exception) {
                showError("خطا در شروع نصب: ${e.message}")
            }
        }
    }
    
    private fun observeProgress() {
        lifecycleScope.launch {
            HarnessState.status.collect { status ->
                when (status.phase) {
                    Phase.RUNNING -> {
                        cardProgress.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        if (!webViewLoaded) {
                            webViewLoaded = true
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = true
                            webView.webViewClient = WebViewClient()
                            webView.loadUrl("http://127.0.0.1:${BootConfig.WEB_PORT}")
                        }
                    }
                    Phase.ERROR -> {
                        showError(status.message)
                    }
                    Phase.BOOTSTRAPPING -> {
                        tvProgressTitle.text = status.message
                        status.downloadProgress?.let { progress ->
                            progressBar.progress = progress.percentage
                            tvProgressDetails.text = buildString {
                                append("${FileUtils.formatBytes(progress.bytesDownloaded)} / ${FileUtils.formatBytes(progress.totalBytes)}\n")
                                if (progress.speedBytesPerSecond > 0) {
                                    append("سرعت: ${FileUtils.formatBytes(progress.speedBytesPerSecond)}/s\n")
                                    if (progress.etaSeconds > 0) {
                                        val mins = progress.etaSeconds / 60
                                        val secs = progress.etaSeconds % 60
                                        append("زمان باقی‌مانده: ${mins}m ${secs}s")
                                    }
                                }
                            }
                        }
                    }
                    Phase.STARTING_DSH -> {
                        tvProgressTitle.text = status.message
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("خطا")
            .setMessage(message)
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun showDownloadFailedDialog(fileName: String, url: String) {
        val editText = EditText(this).apply {
            hint = getString(R.string.dialog_custom_url_hint)
            setText(url)
            setPadding(32, 16, 32, 16)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_download_failed_title))
            .setMessage(getString(R.string.dialog_download_failed_message, fileName))
            .setView(editText)
            .setPositiveButton(getString(R.string.btn_retry)) { dialog, _ ->
                val newUrl = editText.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    // Retry with new URL
                    lifecycleScope.launch {
                        try {
                            val installer = BootstrapInstaller(this@MainActivity)
                            installer.install(mapOf(fileName to newUrl))
                        } catch (e: Exception) {
                            showError("خطا: ${e.message}")
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
