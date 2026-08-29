package com.yahyapro20.dshmobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase {
    IDLE, BOOTSTRAPPING, STARTING_DSH, RUNNING, ERROR
}

data class DownloadProgress(
    val fileName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long = 0L,
    val percentage: Int = 0
)

data class Status(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val errorDetail: String? = null,
    val downloadProgress: DownloadProgress? = null,
    val canRetry: Boolean = false,
    val failedUrl: String? = null,
    val failedFileName: String? = null
)

object HarnessState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(
        phase: Phase,
        message: String,
        errorDetail: String? = null,
        downloadProgress: DownloadProgress? = null,
        canRetry: Boolean = false,
        failedUrl: String? = null,
        failedFileName: String? = null
    ) {
        _status.value = Status(
            phase = phase,
            message = message,
            errorDetail = errorDetail,
            downloadProgress = downloadProgress,
            canRetry = canRetry,
            failedUrl = failedUrl,
            failedFileName = failedFileName
        )
    }

    fun clear() {
        _status.value = Status()
    }
}
