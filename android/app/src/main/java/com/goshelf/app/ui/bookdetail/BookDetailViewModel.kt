package com.goshelf.app.ui.bookdetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.goshelf.app.data.api.BookDetail
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.SettingsRepository
import com.goshelf.app.data.repository.StarRepository
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
    val isPaused: Boolean = false,
    val isStarred: Boolean = false,
    val isAlreadyDownloaded: Boolean = false
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val starRepository: StarRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "BookDetailViewModel"
    }

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val workManager = WorkManager.getInstance(context)
    private var currentBookId: Int = 0

    fun loadBook(bookId: Int) {
        currentBookId = bookId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val book = bookRepository.getBookDetail(bookId)
                val isStarred = starRepository.isStarred("book", bookId.toString())
                val isDownloaded = starRepository.isDownloaded(bookId)
                _uiState.value = _uiState.value.copy(
                    book = book,
                    isLoading = false,
                    isStarred = isStarred,
                    isAlreadyDownloaded = isDownloaded
                )

                checkForPartialDownload(bookId)
                observeExistingWork(bookId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load book details"
                )
            }
        }
    }

    fun toggleStar() {
        viewModelScope.launch {
            val book = _uiState.value.book ?: return@launch
            val isNowStarred = starRepository.toggleStar(
                "book", currentBookId.toString(), book.title
            )
            _uiState.value = _uiState.value.copy(isStarred = isNowStarred)
        }
    }

    fun removeDownload(bookId: Int) {
        viewModelScope.launch {
            val download = starRepository.getDownload(bookId)
            if (download != null) {
                // Try to delete the files from the device
                try {
                    val uri = Uri.parse(download.outputDirUri)
                    val docFile = DocumentFile.fromTreeUri(context, uri)
                    docFile?.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete download files for book $bookId", e)
                }
            }
            starRepository.removeDownload(bookId)
            _uiState.value = _uiState.value.copy(isAlreadyDownloaded = false)
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

        if (downloadDir.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "No download folder selected. Please choose one in Settings."
            )
            return
        }

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

        // Use REPLACE to allow re-download
        workManager.enqueueUniqueWork(
            "download_book_$bookId",
            ExistingWorkPolicy.REPLACE,
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
        startDownload(bookId, bookTitle)
    }

    fun cancelDownload(bookId: Int) {
        workManager.cancelUniqueWork("download_book_$bookId")

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
                            downloadStatus = "Download complete",
                            isDownloading = false,
                            isPaused = false,
                            hasPartialDownload = false,
                            isAlreadyDownloaded = true
                        )
                        // Record the download in the database
                        viewModelScope.launch {
                            val book = _uiState.value.book
                            if (book != null) {
                                starRepository.markDownloaded(
                                    bookId = bookId,
                                    title = book.title,
                                    author = book.author,
                                    outputDirUri = outputDir,
                                    totalSize = book.totalSize
                                )
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Download failed"
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = -1,
                            downloadStatus = error,
                            isDownloading = false,
                            isPaused = false
                        )
                        checkForPartialDownload(bookId)
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = -1,
                            downloadStatus = "Cancelled",
                            isDownloading = false
                        )
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
