package com.yahyapro20.dshmobile

import android.content.Context
import android.util.Log
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

fun installBootstrap(context: Context, customUrls: Map<String, String> = emptyMap()) {
    try {
        HarnessState.update(Phase.BOOTSTRAPPING, "Starting bootstrap installation...")
        
        val urls = mapOf(
            "proot" to (customUrls["proot"] ?: BootConfig.PROOT_URL),
            "rootfs" to (customUrls["rootfs"] ?: BootConfig.ROOTFS_URL),
            "node" to (customUrls["node"] ?: BootConfig.NODE_URL)
        ).mapValues { it.value.trim() }

        installProot(context, urls["proot"]!!)
        installRootfs(context, urls["rootfs"]!!)
        installNode(context, urls["node"]!!)
        
        HarnessState.update(Phase.STARTING_DSH, "Bootstrap installed. Starting DeepSeek Harness...")
    } catch (e: Exception) {
        Log.e(TAG, "Bootstrap installation failed", e)
        HarnessState.update(
            Phase.ERROR,
            "Bootstrap installation failed: ${e.message}",
            errorDetail = e.stackTraceToString(),
            canRetry = true,
            failedUrl = e.message?.substringAfter("for ") ?: "Unknown URL"
        )
        throw e
    }
}

private fun installProot(context: Context, url: String) {
    // MUST be in cacheDir for Android 10+ execution permission
    val prootFile = File(context.cacheDir, "proot")
    if (prootFile.exists() && prootFile.canExecute()) {
        Log.i(TAG, "proot already installed at ${prootFile.absolutePath}")
        return
    }

    var lastError: Exception? = null
    for (attempt in 1..MAX_RETRIES) {
        try {
            Log.i(TAG, "Downloading proot (attempt $attempt/$MAX_RETRIES)")
            HarnessState.update(
                Phase.BOOTSTRAPPING,
                "Downloading proot... (Attempt $attempt/$MAX_RETRIES)",
                downloadProgress = DownloadProgress(fileName = "proot")
            )
            
            val cached = File(localCacheDir(context), BootConfig.LOCAL_PROOT_FILENAME)
            val tmp: File = if (cached.exists()) {
                Log.i(TAG, "Using cached proot from ${cached.absolutePath}")
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
            
            if (!cached.exists()) tmp.delete()
            Log.i(TAG, "proot installed successfully at ${prootFile.absolutePath}")
            return
        } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "proot download attempt $attempt failed: ${e.message}")
            if (attempt < MAX_RETRIES) {
                HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                delay(RETRY_DELAY_MS)
            }
        }
    }
    throw IllegalStateException("Failed to download proot after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
}

private fun installRootfs(context: Context, url: String) {
    if (File(rootfsDir(context), "bin/sh").exists()) {
        Log.i(TAG, "rootfs already installed")
        return
    }

    var lastError: Exception? = null
    for (attempt in 1..MAX_RETRIES) {
        try {
            Log.i(TAG, "Downloading rootfs (attempt $attempt/$MAX_RETRIES)")
            HarnessState.update(
                Phase.BOOTSTRAPPING,
                "Downloading Linux root filesystem... (Attempt $attempt/$MAX_RETRIES)",
                downloadProgress = DownloadProgress(fileName = "rootfs.tar.xz")
            )
            
            val cached = File(localCacheDir(context), BootConfig.LOCAL_ROOTFS_FILENAME)
            val archive: File = if (cached.exists()) {
                Log.i(TAG, "Using cached rootfs from ${cached.absolutePath}")
                cached
            } else {
                val tmp = File(context.cacheDir, "rootfs-download.tar.xz")
                downloadFileWithProgress(url, tmp, "rootfs.tar.xz")
                tmp
            }
            
            HarnessState.update(Phase.BOOTSTRAPPING, "Extracting root filesystem (this may take a few minutes)...")
            extractTarXz(archive, rootfsDir(context))
            if (!cached.exists()) archive.delete()

            listOf("tmp", "root", "usr/local/bin", "opt").forEach {
                File(rootfsDir(context), it).mkdirs()
            }
            
            Log.i(TAG, "rootfs installed successfully")
            return
        } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "rootfs download attempt $attempt failed: ${e.message}")
            if (attempt < MAX_RETRIES) {
                HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                delay(RETRY_DELAY_MS)
            }
        }
    }
    throw IllegalStateException("Failed to download rootfs after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
}

private fun installNode(context: Context, url: String) {
    val nodeDir = File(rootfsDir(context), BootConfig.NODE_DIR_NAME)
    if (nodeDir.exists()) {
        Log.i(TAG, "Node.js already installed")
        return
    }

    var lastError: Exception? = null
    for (attempt in 1..MAX_RETRIES) {
        try {
            Log.i(TAG, "Downloading Node.js (attempt $attempt/$MAX_RETRIES)")
            HarnessState.update(
                Phase.BOOTSTRAPPING,
                "Downloading Node.js runtime... (Attempt $attempt/$MAX_RETRIES)",
                downloadProgress = DownloadProgress(fileName = "node.tar.xz")
            )
            
            val cached = File(localCacheDir(context), BootConfig.LOCAL_NODE_FILENAME)
            val archive: File = if (cached.exists()) {
                Log.i(TAG, "Using cached Node.js from ${cached.absolutePath}")
                cached
            } else {
                val tmp = File(context.cacheDir, "node-download.tar.xz")
                downloadFileWithProgress(url, tmp, "node.tar.xz")
                tmp
            }
            
            HarnessState.update(Phase.BOOTSTRAPPING, "Extracting Node.js runtime...")
            extractTarXz(archive, rootfsDir(context))
            if (!cached.exists()) archive.delete()
            
            Log.i(TAG, "Node.js installed successfully")
            return
        } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "Node.js download attempt $attempt failed: ${e.message}")
            if (attempt < MAX_RETRIES) {
                HarnessState.update(Phase.BOOTSTRAPPING, "Download failed. Retrying in ${RETRY_DELAY_MS / 1000}s...", canRetry = false)
                delay(RETRY_DELAY_MS)
            }
        }
    }
    throw IllegalStateException("Failed to download Node.js after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
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
            
            val existingSize = if (dest.exists()) dest.length() else 0L
            if (existingSize > 0) {
                conn.setRequestProperty("Range", "bytes=$existingSize-")
                Log.i(TAG, "Resuming download from byte $existingSize")
            }
            
            conn.connect()
            val code = conn.responseCode
            
            when {
                code in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect with no Location header")
                    conn.disconnect()
                    redirectsLeft--
                    currentUrl = location
                    continue
                }
                code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL -> {
                    val contentLength = if (code == HttpURLConnection.HTTP_PARTIAL) {
                        conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: -1L
                    } else {
                        conn.contentLength.toLong()
                    }
                    
                    dest.parentFile?.mkdirs()
                    val output = if (existingSize > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                        FileOutputStream(dest, true)
                    } else {
                        FileOutputStream(dest)
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
                                
                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                                    val bytesDelta = totalBytesRead - lastBytesRead
                                    val timeDelta = now - lastProgressTime
                                    val speed = (bytesDelta * 1000 / timeDelta).coerceAtLeast(0)
                                    val percentage = if (contentLength > 0) ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100) else 0
                                    val eta = if (speed > 0 && contentLength > 0) (contentLength - totalBytesRead) / speed else 0
                                    
                                    HarnessState.update(
                                        Phase.BOOTSTRAPPING,
                                        "Downloading $fileName...",
                                        downloadProgress = DownloadProgress(
                                            fileName = fileName,
                                            bytesDownloaded = totalBytesRead,
                                            totalBytes = contentLength,
                                            speedBytesPerSecond = speed,
                                            etaSeconds = eta,
                                            percentage = percentage
                                        )
                                    )
                                    lastProgressTime = now
                                    lastBytesRead = totalBytesRead
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    Log.i(TAG, "Download complete: ${dest.absolutePath} ($totalBytesRead bytes)")
                    return
                }
                else -> {
                    conn.disconnect()
                    throw IllegalStateException("Download failed ($code) for $currentUrl")
                }
            }
        } catch (e: Exception) {
            lastError = e
            Log.e(TAG, "Download error for $currentUrl: ${e.message}")
            break
        }
    }
    throw lastError ?: IllegalStateException("Download failed after redirects")
}

private fun extractTarXz(archiveFile: File, destDir: File) {
    destDir.mkdirs()
    var extractedCount = 0
    var lastProgressUpdate = System.currentTimeMillis()
    
    BufferedInputStream(archiveFile.inputStream()).use { bin ->
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
                                java.nio.file.Files.createSymbolicLink(outFile.toPath(), File(entry.linkName).toPath())
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping symlink ${entry.name}: ${e.message}")
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
                        HarnessState.update(Phase.BOOTSTRAPPING, "Extracting files... ($extractedCount files)")
                        lastProgressUpdate = now
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }
    Log.i(TAG, "Extraction complete: $extractedCount files extracted to ${destDir.absolutePath}")
}

private fun rootfsDir(context: Context) = File(context.filesDir, "dsh-root")
private fun localCacheDir(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), BootConfig.LOCAL_CACHE_SUBDIR)
    dir.mkdirs()
    return dir
}

private suspend fun delay(ms: Long) {
    kotlinx.coroutines.delay(ms)
}
