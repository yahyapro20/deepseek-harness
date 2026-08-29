package com.yahyapro20.dshmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.getSystemService
import kotlinx.coroutines.delay
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "BootstrapInstaller"
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 2000L
private const val DOWNLOAD_BUFFER_SIZE = 8192
private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
private const val NOTIFICATION_CHANNEL_ID = "bootstrap_download"
private const val NOTIFICATION_ID = 1001

class BootstrapInstaller(private val context: Context) {
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bootstrap Download",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress for bootstrap files"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun updateNotification(progress: DownloadProgress) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading ${progress.fileName}")
            .setContentText("${FileUtils.formatBytes(progress.bytesDownloaded)} / ${FileUtils.formatBytes(progress.totalBytes)}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.percentage, false)
            .setOngoing(true)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun clearNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
    
    suspend fun install(customUrls: Map<String, String> = emptyMap()) {
        try {
            HarnessState.update(Phase.BOOTSTRAPPING, "Starting bootstrap installation...")
            
            val urls = mapOf(
                "proot" to (customUrls["proot"] ?: BootConfig.PROOT_URL),
                "rootfs" to (customUrls["rootfs"] ?: BootConfig.ROOTFS_URL),
                "node" to (customUrls["node"] ?: BootConfig.NODE_URL)
            ).mapValues { it.value.trim() }

            installProot(urls["proot"]!!)
            installRootfs(urls["rootfs"]!!)
            installNode(urls["node"]!!)
            
            clearNotification()
            HarnessState.update(Phase.STARTING_DSH, "Bootstrap installed. Starting DeepSeek Harness...")
        } catch (e: Exception) {
            clearNotification()
            throw e
        }
    }

    private suspend fun installProot(url: String) {
        val prootFile = File(context.cacheDir, "proot")
        if (prootFile.exists() && prootFile.canExecute()) {
            // Also check external storage for persistence
            val externalProot = getExternalCacheFile(BootConfig.LOCAL_PROOT_FILENAME)
            if (externalProot.exists()) {
                externalProot.copyTo(prootFile, overwrite = true)
                prootFile.setExecutable(true)
            }
            if (prootFile.canExecute()) {
                android.util.Log.i(TAG, "proot already installed")
                return
            }
        }

        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                android.util.Log.i(TAG, "Downloading proot (attempt $attempt/$MAX_RETRIES)")
                
                val progress = DownloadProgress(fileName = "proot", isResuming = prootFile.exists())
                HarnessState.update(Phase.BOOTSTRAPPING, "Downloading proot... (Attempt $attempt/$MAX_RETRIES)", downloadProgress = progress)
                updateNotification(progress)
                
                val cached = getExternalCacheFile(BootConfig.LOCAL_PROOT_FILENAME)
                val tmp: File = if (cached.exists() && FileUtils.verifyChecksum(cached, BootConfig.PROOT_SHA256)) {
                    android.util.Log.i(TAG, "Using cached proot from ${cached.absolutePath}")
                    cached
                } else {
                    val t = File(context.cacheDir, "proot-download")
                    downloadFileWithProgress(url, t, "proot")
                    t
                }

                prootFile.parentFile?.mkdirs()
                tmp.copyTo(prootFile, overwrite = true)
                prootFile.setExecutable(true, false)
                prootFile.setReadable(true, false)
                
                // Copy to external storage for persistence
                tmp.copyTo(getExternalCacheFile(BootConfig.LOCAL_PROOT_FILENAME), overwrite = true)
                
                if (!cached.exists()) tmp.delete()
                android.util.Log.i(TAG, "proot installed successfully")
                return
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "proot download attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw IllegalStateException("Failed to download proot after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
    }

    private suspend fun installRootfs(url: String) {
        if (File(rootfsDir(), "bin/sh").exists()) {
            android.util.Log.i(TAG, "rootfs already installed")
            return
        }

        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                android.util.Log.i(TAG, "Downloading rootfs (attempt $attempt/$MAX_RETRIES)")
                
                val progress = DownloadProgress(fileName = "rootfs.tar.xz", isResuming = File(context.cacheDir, "rootfs-download.tar.xz").exists())
                HarnessState.update(Phase.BOOTSTRAPPING, "Downloading Linux root filesystem... (Attempt $attempt/$MAX_RETRIES)", downloadProgress = progress)
                updateNotification(progress)
                
                val cached = getExternalCacheFile(BootConfig.LOCAL_ROOTFS_FILENAME)
                val archive: File = if (cached.exists() && FileUtils.verifyChecksum(cached, BootConfig.ROOTFS_SHA256)) {
                    android.util.Log.i(TAG, "Using cached rootfs from ${cached.absolutePath}")
                    cached
                } else {
                    val tmp = File(context.cacheDir, "rootfs-download.tar.xz")
                    downloadFileWithProgress(url, tmp, "rootfs.tar.xz")
                    tmp
                }
                
                HarnessState.update(Phase.BOOTSTRAPPING, "Extracting root filesystem (this may take a few minutes)...")
                extractTarXz(archive, rootfsDir())
                if (!cached.exists()) {
                    archive.copyTo(getExternalCacheFile(BootConfig.LOCAL_ROOTFS_FILENAME), overwrite = true)
                    archive.delete()
                }

                listOf("tmp", "root", "usr/local/bin", "opt").forEach {
                    File(rootfsDir(), it).mkdirs()
                }
                
                android.util.Log.i(TAG, "rootfs installed successfully")
                return
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "rootfs download attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw IllegalStateException("Failed to download rootfs after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
    }

    private suspend fun installNode(url: String) {
    val nodeDir = File(rootfsDir(), BootConfig.NODE_DIR_NAME)
    if (nodeDir.exists()) {
        android.util.Log.i(TAG, "Node.js already installed")
        return
    }

    var lastError: Exception? = null
    for (attempt in 1..MAX_RETRIES) {
        try {
            android.util.Log.i(TAG, "Downloading Node.js (attempt $attempt/$MAX_RETRIES)")
            
            val progress = DownloadProgress(fileName = "node.tar.gz", isResuming = File(context.cacheDir, "node-download.tar.gz").exists())
            HarnessState.update(Phase.BOOTSTRAPPING, "Downloading Node.js runtime... (Attempt $attempt/$MAX_RETRIES)", downloadProgress = progress)
            updateNotification(progress)
            
            val cached = getExternalCacheFile(BootConfig.LOCAL_NODE_FILENAME)
            val archive: File = if (cached.exists() && FileUtils.verifyChecksum(cached, BootConfig.NODE_SHA256)) {
                android.util.Log.i(TAG, "Using cached Node.js from ${cached.absolutePath}")
                cached
            } else {
                val tmp = File(context.cacheDir, "node-download.tar.gz")
                downloadFileWithProgress(url, tmp, "node.tar.gz")
                tmp
            }
            
            HarnessState.update(Phase.BOOTSTRAPPING, "Extracting Node.js runtime...")
            extractTarGz(archive, rootfsDir())  // Changed from extractTarXz
            if (!cached.exists()) {
                archive.copyTo(getExternalCacheFile(BootConfig.LOCAL_NODE_FILENAME), overwrite = true)
                archive.delete()
            }
            
            android.util.Log.i(TAG, "Node.js installed successfully")
            return
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w(TAG, "Node.js download attempt $attempt failed: ${e.message}")
            if (attempt < MAX_RETRIES) {
                HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                delay(RETRY_DELAY_MS)
            }
        }
    }
    throw IllegalStateException("Failed to download Node.js after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
}
private fun extractTarGz(archiveFile: File, destDir: File) {
    destDir.mkdirs()
    var extractedCount = 0
    var lastProgressUpdate = System.currentTimeMillis()
    
    java.io.BufferedInputStream(archiveFile.inputStream()).use { bin ->
        org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(bin).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    
                    if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Blocked path traversal: ${entry.name}")
                    }
                    
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            try {
                                outFile.parentFile?.mkdirs()
                                outFile.delete()
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(),
                                    File(entry.linkName).toPath()
                                )
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "Skipping symlink ${entry.name}: ${e.message}")
                            }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            val mode = entry.mode
                            if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                            outFile.setReadable(true, false)
                            outFile.setWritable(true, true)
                            extractedCount++
                        }
                    }
                    
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate >= 2000L) {
                        HarnessState.update(
                            Phase.BOOTSTRAPPING,
                            "Extracting files... ($extractedCount files)"
                        )
                        lastProgressUpdate = now
                    }
                    
                    entry = tar.nextTarEntry
                }
            }
        }
    }
    
    android.util.Log.i(TAG, "Extraction complete: $extractedCount files extracted")
}
    private suspend fun downloadFileWithProgress(urlString: String, dest: File, fileName: String) {
        var currentUrl = urlString
        var redirectsLeft = 5
        var lastError: Exception? = null
        
        while (redirectsLeft > 0) {
            try {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                
                // Support resume if file partially exists
                val existingSize = if (dest.exists()) dest.length() else 0L
                if (existingSize > 0) {
                    conn.setRequestProperty("Range", "bytes=$existingSize-")
                    android.util.Log.i(TAG, "Resuming download from byte $existingSize")
                }
                
                conn.connect()
                val code = conn.responseCode
                
                when {
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect with no Location header")
                        conn.disconnect()
                        redirectsLeft--
                        currentUrl = location
                        continue
                    }
                    code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL -> {
                        val contentLength = if (code == HttpURLConnection.HTTP_PARTIAL) {
                            conn.getHeaderField("Content-Range")
                                ?.substringAfter("/")
                                ?.toLongOrNull()
                                ?: -1L
                        } else {
                            conn.contentLength.toLong()
                        }
                        
                        dest.parentFile?.mkdirs()
                        val output = if (existingSize > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                            FileOutputStream(dest, true) // Append mode
                        } else {
                            FileOutputStream(dest) // Fresh download
                        }
                        
                        output.use { fos ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var totalBytesRead = existingSize
                            var lastProgressTime = System.currentTimeMillis()
                            var lastBytesRead = totalBytesRead
                            
                            conn.inputStream.use { input ->
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead
                                    
                                    // Update progress periodically
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                                        val bytesDelta = totalBytesRead - lastBytesRead
                                        val timeDelta = now - lastProgressTime
                                        val speed = (bytesDelta * 1000 / timeDelta).coerceAtLeast(0)
                                        
                                        val percentage = if (contentLength > 0) {
                                            ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                        } else {
                                            0
                                        }
                                        
                                        val eta = if (speed > 0 && contentLength > 0) {
                                            (contentLength - totalBytesRead) / speed
                                        } else {
                                            0
                                        }
                                        
                                        val progress = DownloadProgress(
                                            fileName = fileName,
                                            bytesDownloaded = totalBytesRead,
                                            totalBytes = contentLength,
                                            speedBytesPerSecond = speed,
                                            etaSeconds = eta,
                                            percentage = percentage,
                                            isResuming = existingSize > 0
                                        )
                                        
                                        HarnessState.update(
                                            Phase.BOOTSTRAPPING,
                                            "Downloading $fileName...",
                                            downloadProgress = progress
                                        )
                                        updateNotification(progress)
                                        
                                        lastProgressTime = now
                                        lastBytesRead = totalBytesRead
                                    }
                                }
                            }
                        }
                        
                        conn.disconnect()
                        android.util.Log.i(TAG, "Download complete: ${dest.absolutePath} (${FileUtils.formatBytes(totalBytesRead)})")
                        return
                    }
                    else -> {
                        conn.disconnect()
                        throw IllegalStateException("Download failed ($code) for $currentUrl")
                    }
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.e(TAG, "Download error for $currentUrl: ${e.message}")
                break
            }
        }
        
        throw lastError ?: IllegalStateException("Download failed after redirects")
    }

    private fun extractTarXz(archiveFile: File, destDir: File) {
        destDir.mkdirs()
        var extractedCount = 0
        var lastProgressUpdate = System.currentTimeMillis()
        
        java.io.BufferedInputStream(archiveFile.inputStream()).use { bin ->
            XZCompressorInputStream(bin).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            throw SecurityException("Blocked path traversal: ${entry.name}")
                        }
                        
                        when {
                            entry.isDirectory -> outFile.mkdirs()
                            entry.isSymbolicLink -> {
                                try {
                                    outFile.parentFile?.mkdirs()
                                    outFile.delete()
                                    java.nio.file.Files.createSymbolicLink(
                                        outFile.toPath(),
                                        File(entry.linkName).toPath()
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.w(TAG, "Skipping symlink ${entry.name}: ${e.message}")
                                }
                            }
                            else -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                                val mode = entry.mode
                                if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                                outFile.setWritable(true, true)
                                extractedCount++
                            }
                        }
                        
                        // Periodic progress update during extraction
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 2000L) {
                            HarnessState.update(
                                Phase.BOOTSTRAPPING,
                                "Extracting files... ($extractedCount files)"
                            )
                            lastProgressUpdate = now
                        }
                        
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
        
        android.util.Log.i(TAG, "Extraction complete: $extractedCount files extracted")
    }

    private fun rootfsDir() = File(context.filesDir, "dsh-root")
    
    private fun getExternalCacheFile(filename: String): File {
        val externalDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External storage not available")
        val cacheDir = File(externalDir, BootConfig.LOCAL_CACHE_SUBDIR)
        cacheDir.mkdirs()
        return File(cacheDir, filename)
    }
}
