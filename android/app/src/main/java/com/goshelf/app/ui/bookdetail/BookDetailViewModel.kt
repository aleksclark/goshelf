package com.goshelf.app.ui.bookdetail

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.goshelf.app.data.api.BookDetail
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.SettingsRepository
import com.goshelf.app.data.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class BookDetailUiState(
    val book: BookDetail? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloadProgress: Int = -1,
    val downloadStatus: String? = null,
    val isDownloading: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadSpeed: Long = 0L,
    val hasPartialDownload: Boolean = false,
    val isPaused: Boolean = false
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "BookDetailViewModel"
    }

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val workManager = WorkManager.getInstance(context)

    fun loadBook(bookId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val book = bookRepository.getBookDetail(bookId)
                _uiState.value = _uiState.value.copy(book = book, isLoading = false)

                // Check for existing partial download
                checkForPartialDownload(bookId)

                // Check if work is already running
                observeExistingWork(bookId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load book details"
                )
            }
        }
    }

    private fun checkForPartialDownload(bookId: Int) {
        val downloadsDir = File(context.cacheDir, "downloads")
        val metadataFile = File(downloadsDir, "book_${bookId}.download")
        val partFile = File(downloadsDir, "book_${bookId}.part")

        if (metadataFile.exists() && partFile.exists()) {
            _uiState.value = _uiState.value.copy(
                hasPartialDownload = true,
                isPaused = true,
                bytesDownloaded = partFile.length()
            )
            Log.i(TAG, "Found partial download for book $bookId: ${partFile.length()} bytes")
        }
    }

    private fun observeExistingWork(bookId: Int) {
        val workInfos = workManager.getWorkInfosForUniqueWork("download_book_$bookId")
        viewModelScope.launch {
            try {
                val infos = workInfos.get()
                val activeWork = infos.firstOrNull { info ->
                    info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                }
                if (activeWork != null) {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = true,
                        isPaused = false
                    )
                    observeWorkProgress(bookId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check existing work for book $bookId", e)
            }
        }
    }

    fun getCoverUrl(bookId: Int): String {
        return bookRepository.getCoverUrl(bookId)
    }

    fun startDownload(bookId: Int, bookTitle: String) {
        val downloadDir = settingsRepository.getDownloadDirectory()

        val inputData = Data.Builder()
            .putInt(DownloadWorker.KEY_BOOK_ID, bookId)
            .putString(DownloadWorker.KEY_BOOK_TITLE, bookTitle)
            .putString(DownloadWorker.KEY_DOWNLOAD_DIR, downloadDir)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag("download_$bookId")
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        // Use KEEP so re-tapping doesn't restart an active download
        workManager.enqueueUniqueWork(
            "download_book_$bookId",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            downloadProgress = 0,
            isPaused = false,
            hasPartialDownload = false
        )

        observeWorkProgress(bookId)
    }

    fun resumeDownload(bookId: Int, bookTitle: String) {
        // Resume is the same as start - the worker will detect the .download file
        // and resume from where it left off
        startDownload(bookId, bookTitle)
    }

    fun cancelDownload(bookId: Int) {
        workManager.cancelUniqueWork("download_book_$bookId")

        // Clean up partial files
        val downloadsDir = File(context.cacheDir, "downloads")
        File(downloadsDir, "book_${bookId}.download").delete()
        File(downloadsDir, "book_${bookId}.part").delete()

        _uiState.value = _uiState.value.copy(
            isDownloading = false,
            downloadProgress = -1,
            downloadStatus = "Cancelled",
            hasPartialDownload = false,
            isPaused = false
        )
    }

    fun pauseDownload(bookId: Int) {
        // Cancel the work but keep partial files for resume
        workManager.cancelUniqueWork("download_book_$bookId")

        _uiState.value = _uiState.value.copy(
            isDownloading = false,
            isPaused = true,
            hasPartialDownload = true,
            downloadStatus = "Paused"
        )
    }

    private fun observeWorkProgress(bookId: Int) {
        workManager.getWorkInfosForUniqueWorkLiveData("download_book_$bookId")
            .observeForever { workInfos ->
                val workInfo = workInfos?.firstOrNull() ?: return@observeForever

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                        val status = workInfo.progress.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Downloading..."
                        val bytesDownloaded = workInfo.progress.getLong(DownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                        val totalBytes = workInfo.progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L)
                        val speed = workInfo.progress.getLong(DownloadWorker.KEY_DOWNLOAD_SPEED, 0L)

                        _uiState.value = _uiState.value.copy(
                            downloadProgress = progress,
                            downloadStatus = status,
                            isDownloading = true,
                            isPaused = false,
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = totalBytes,
                            downloadSpeed = speed
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputDir = workInfo.outputData.getString(DownloadWorker.KEY_OUTPUT_DIR) ?: ""
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = 100,
                            downloadStatus = "Downloaded to $outputDir",
                            isDownloading = false,
                            isPaused = false,
                            hasPartialDownload = false
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Download failed"
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = -1,
                            downloadStatus = error,
                            isDownloading = false,
                            isPaused = false
                        )
                        // Check if partial download exists for resume
                        checkForPartialDownload(bookId)
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = -1,
                            downloadStatus = "Cancelled",
                            isDownloading = false
                        )
                        // Check if it was a pause (partial files still exist)
                        checkForPartialDownload(bookId)
                    }
                    WorkInfo.State.ENQUEUED -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = true,
                            downloadStatus = "Waiting for network...",
                            isPaused = false
                        )
                    }
                    WorkInfo.State.BLOCKED -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = true,
                            downloadStatus = "Waiting...",
                            isPaused = false
                        )
                    }
                }
            }
    }
}
