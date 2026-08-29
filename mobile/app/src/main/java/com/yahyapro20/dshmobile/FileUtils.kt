package com.yahyapro20.dshmobile

import android.util.Log
import java.io.File
import java.security.MessageDigest

object FileUtils {
    private const val TAG = "FileUtils"
    
    /**
     * Calculate SHA256 checksum of a file
     */
    fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Verify file checksum against expected value
     * Returns true if checksum matches or if expected is empty
     */
    fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        if (expectedChecksum.isEmpty()) {
            Log.w(TAG, "No checksum provided for ${file.name}, skipping verification")
            return true
        }
        
        val actualChecksum = calculateSHA256(file)
        val isValid = actualChecksum.equals(expectedChecksum, ignoreCase = true)
        
        if (isValid) {
            Log.i(TAG, "Checksum verified for ${file.name}")
        } else {
            Log.e(TAG, "Checksum mismatch for ${file.name}!\nExpected: $expectedChecksum\nActual: $actualChecksum")
        }
        
        return isValid
    }
    
    /**
     * Get file size in bytes
     */
    fun getFileSize(file: File): Long = file.length()
    
    /**
     * Format bytes to human readable string
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
    
    /**
     * Delete file safely
     */
    fun deleteFile(file: File): Boolean {
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }
}
