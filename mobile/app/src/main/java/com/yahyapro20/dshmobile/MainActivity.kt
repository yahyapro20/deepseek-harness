package com.yahyapro20.dshmobile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yahyapro20.dshmobile.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedFiles = mutableMapOf<String, Uri>()
    private val downloadQueued = mutableSetOf<String>()
    private var webViewLoaded = false

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        checkExistingFiles()
    }

    private fun setupClickListeners() {
        binding.btnSelectProot.setOnClickListener { prootFileLauncher.launch("*/*") }
        binding.btnSelectRootfs.setOnClickListener { rootfsFileLauncher.launch("*/*") }
        binding.btnSelectNode.setOnClickListener { nodeFileLauncher.launch("*/*") }

        binding.btnDownloadProot.setOnClickListener { queueDownload("proot", BootConfig.PROOT_URL) }
        binding.btnDownloadRootfs.setOnClickListener { queueDownload("rootfs", BootConfig.ROOTFS_URL) }
        binding.btnDownloadNode.setOnClickListener { queueDownload("node", BootConfig.NODE_URL) }

        binding.btnDownloadAll.setOnClickListener {
            if (!selectedFiles.containsKey("proot")) queueDownload("proot", BootConfig.PROOT_URL)
            if (!selectedFiles.containsKey("rootfs")) queueDownload("rootfs", BootConfig.ROOTFS_URL)
            if (!selectedFiles.containsKey("node")) queueDownload("node", BootConfig.NODE_URL)
            updateUI()
        }

        binding.btnStartInstall.setOnClickListener { startInstallation() }
    }

    private fun checkExistingFiles() {
        val externalDir = getExternalFilesDir(null) ?: return
        val cacheDir = File(externalDir, BootConfig.LOCAL_CACHE_SUBDIR)
        
        if (File(cacheDir, BootConfig.LOCAL_PROOT_FILENAME).exists()) selectedFiles["proot"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_PROOT_FILENAME))
        if (File(cacheDir, BootConfig.LOCAL_ROOTFS_FILENAME).exists()) selectedFiles["rootfs"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_ROOTFS_FILENAME))
        if (File(cacheDir, BootConfig.LOCAL_NODE_FILENAME).exists()) selectedFiles["node"] = Uri.fromFile(File(cacheDir, BootConfig.LOCAL_NODE_FILENAME))
        
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
        updateCard(binding.tvProotStatus, "proot")
        updateCard(binding.tvRootfsStatus, "rootfs")
        updateCard(binding.tvNodeStatus, "node")

        val allReady = (selectedFiles.containsKey("proot") || downloadQueued.contains("proot")) &&
                      (selectedFiles.containsKey("rootfs") || downloadQueued.contains("rootfs")) &&
                      (selectedFiles.containsKey("node") || downloadQueued.contains("node"))
        binding.btnStartInstall.isEnabled = allReady
    }

    private fun updateCard(statusText: android.widget.TextView, fileType: String) {
        when {
            selectedFiles.containsKey(fileType) -> {
                statusText.text = "✅ انتخاب شده"
                statusText.setTextColor(Color.parseColor("#4CAF50"))
            }
            downloadQueued.contains(fileType) -> {
                statusText.text = "⏳ در صف دانلود"
                statusText.setTextColor(Color.parseColor("#FF9800"))
            }
            else -> {
                statusText.text = "❌ هنوز انتخاب/دانلود نشده"
                statusText.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    private fun startInstallation() {
        binding.setupLayout.visibility = View.GONE
        binding.cardProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                selectedFiles.forEach { (type, uri) ->
                    val destFile = when (type) {
                        "proot" -> File(cacheDir, "proot")
                        "rootfs" -> File(cacheDir, "rootfs-manual.tar.xz")
                        "node" -> File(cacheDir, "node-manual.tar.gz")
                        else -> null
                    }
                    
                    destFile?.let { file ->
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        if (type == "proot") file.setExecutable(true)
                    }
                }

                val serviceIntent = Intent(this@MainActivity, HarnessService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                else startService(serviceIntent)

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
                        binding.cardProgress.visibility = View.GONE
                        binding.webView.visibility = View.VISIBLE
                        if (!webViewLoaded) {
                            webViewLoaded = true
                            binding.webView.settings.javaScriptEnabled = true
                            binding.webView.settings.domStorageEnabled = true
                            binding.webView.webViewClient = WebViewClient()
                            binding.webView.loadUrl("http://127.0.0.1:${BootConfig.WEB_PORT}")
                        }
                    }
                    Phase.ERROR -> showError(status.message)
                    Phase.BOOTSTRAPPING -> {
                        binding.tvProgressTitle.text = status.message
                        status.downloadProgress?.let { progress ->
                            binding.progressBar.progress = progress.percentage
                            binding.tvProgressDetails.text = buildString {
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
                    Phase.STARTING_DSH -> binding.tvProgressTitle.text = status.message
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
}
