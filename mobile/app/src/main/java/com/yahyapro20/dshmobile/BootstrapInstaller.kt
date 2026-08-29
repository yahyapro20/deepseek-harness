package com.yahyapro20.dshmobile

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prepares everything ProotRunner needs: a `proot` binary, an extracted Debian
 * rootfs, an extracted Node.js build, and a one-time `npm install -g dsh` run
 * inside that rootfs. Idempotent — safe to call every app start; it no-ops once
 * the ".installed" marker exists.
 */
object BootstrapInstaller {
    private const val TAG = "BootstrapInstaller"

    private fun rootDir(context: Context) = File(context.filesDir, "dsh-root")
    private fun prootBin(context: Context) = File(context.filesDir, "bin/proot")
    private fun markerFile(context: Context) = File(context.filesDir, ".installed")
    private fun localCacheDir(context: Context) =
        File(context.getExternalFilesDir(null), BootConfig.LOCAL_CACHE_SUBDIR)

    fun isInstalled(context: Context) = markerFile(context).exists()

    /** Runs the full bootstrap. Call from a background thread — this blocks. */
    fun ensureInstalled(context: Context, onProgress: (String) -> Unit) {
        if (isInstalled(context)) {
            Log.i(TAG, "Already installed, skipping bootstrap.")
            return
        }

        context.filesDir.mkdirs()
        File(context.filesDir, "bin").mkdirs()
        rootDir(context).mkdirs()

        onProgress("Getting proot…")
        installProot(context)

        onProgress("Extracting Linux root filesystem…")
        installRootfs(context)

        onProgress("Extracting Node.js…")
        installNode(context)

        onProgress("Installing DeepSeek Harness (first run only)…")
        installDsh(context)

        markerFile(context).writeText(System.currentTimeMillis().toString())
        Log.i(TAG, "Bootstrap complete.")
    }

    // ---- proot ----------------------------------------------------------

    private fun installProot(context: Context) {
        val dest = prootBin(context)
        if (dest.canExecute()) return

        val cached = File(localCacheDir(context), BootConfig.LOCAL_PROOT_FILENAME)
        if (cached.exists()) {
            Log.i(TAG, "Using cached proot from ${cached.absolutePath}")
            cached.copyTo(dest, overwrite = true)
        } else {
            downloadFile(BootConfig.PROOT_URL, dest)
        }
        dest.setExecutable(true, false)
        dest.setReadable(true, false)
    }

    // ---- rootfs -----------------------------------------------------------

    private fun installRootfs(context: Context) {
        if (File(rootDir(context), "bin/sh").exists()) return

        val cached = File(localCacheDir(context), BootConfig.LOCAL_ROOTFS_FILENAME)
        val archive: File = if (cached.exists()) {
            Log.i(TAG, "Using cached rootfs from ${cached.absolutePath}")
            cached
        } else {
            val tmp = File(context.cacheDir, "rootfs-download.tar.xz")
            downloadFile(BootConfig.ROOTFS_URL, tmp)
            tmp
        }
        extractTarXz(archive, rootDir(context))
        if (!cached.exists()) archive.delete()

        // A few directories dsh/node/proot expect to exist and be writable.
        listOf("tmp", "root", "usr/local/bin", "opt").forEach {
            File(rootDir(context), it).mkdirs()
        }
    }

    // ---- node ---------------------------------------------------------------

    private fun installNode(context: Context) {
        val nodeDir = File(rootDir(context), "opt/node")
        if (File(nodeDir, "bin/node").exists()) return

        val cached = File(localCacheDir(context), BootConfig.LOCAL_NODE_FILENAME)
        val archive: File = if (cached.exists()) {
            Log.i(TAG, "Using cached node from ${cached.absolutePath}")
            cached
        } else {
            val tmp = File(context.cacheDir, "node-download.tar.xz")
            downloadFile(BootConfig.NODE_URL, tmp)
            tmp
        }

        val extractedTo = File(context.cacheDir, "node-extract")
        extractedTo.deleteRecursively()
        extractedTo.mkdirs()
        extractTarXz(archive, extractedTo)
        if (!cached.exists()) archive.delete()

        val extractedNodeHome = File(extractedTo, BootConfig.NODE_DIR_NAME)
        nodeDir.parentFile?.mkdirs()
        if (!extractedNodeHome.renameTo(nodeDir)) {
            extractedNodeHome.copyRecursively(nodeDir, overwrite = true)
            extractedNodeHome.deleteRecursively()
        }
        extractedTo.deleteRecursively()

        // The tarball already ships correct executable bits, but they can be lost
        // depending on how the archive was written; make sure the essentials work.
        File(nodeDir, "bin/node").setExecutable(true, false)
    }

    // ---- dsh itself -----------------------------------------------------------

    private fun installDsh(context: Context) {
        val dshBin = File(rootDir(context), "opt/node/bin/dsh")
        if (dshBin.exists()) return

        val exit = ProotRunner.runBlocking(
            context,
            listOf("/opt/node/bin/npm", "install", "-g", BootConfig.DSH_NPM_SPEC)
        )
        if (exit != 0) {
            throw IllegalStateException("npm install -g ${BootConfig.DSH_NPM_SPEC} failed with exit code $exit")
        }
    }

    // ---- shared helpers -----------------------------------------------------------

    private fun downloadFile(urlString: String, dest: File, maxRedirects: Int = 5) {
        Log.i(TAG, "Downloading $urlString -> ${dest.absolutePath}")
        var current = urlString
        var redirectsLeft = maxRedirects
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect with no Location header for $current")
                conn.disconnect()
                if (redirectsLeft-- <= 0) throw IllegalStateException("Too many redirects for $urlString")
                current = location
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("Download failed ($code) for $current")
            }
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 1 shl 16)
                }
            }
            conn.disconnect()
            return
        }
    }

    private fun extractTarXz(archiveFile: File, destDir: File) {
        destDir.mkdirs()
        BufferedInputStream(archiveFile.inputStream()).use { bin ->
            XZCompressorInputStream(bin).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        // Basic path-traversal guard: an entry name should never resolve
                        // outside destDir.
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            throw SecurityException("Blocked path traversal in archive entry: ${entry.name}")
                        }
                        when {
                            entry.isDirectory -> outFile.mkdirs()
                            entry.isSymbolicLink -> {
                                // java.nio symlink creation; ignore failures (some
                                // filesystems/permissions may not support it — dsh/node
                                // don't strictly require every symlink in the rootfs).
                                try {
                                    outFile.parentFile?.mkdirs()
                                    outFile.delete()
                                    java.nio.file.Files.createSymbolicLink(
                                        outFile.toPath(),
                                        java.io.File(entry.linkName).toPath()
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Skipping symlink ${entry.name} -> ${entry.linkName}: ${e.message}")
                                }
                            }
                            else -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                                val mode = entry.mode
                                if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                                outFile.setWritable(true, true)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }
}
