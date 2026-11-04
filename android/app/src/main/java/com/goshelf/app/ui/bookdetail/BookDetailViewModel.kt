package com.goshelf.app.ui.bookdetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.goshelf.app.data.api.BookDetail
import com.goshelf.app.data.download.DownloadStore
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
    val isIndeterminate: Boolean = false,
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
    private val downloadStore = DownloadStore(context.cacheDir)
    private var currentBookId: Int = 0
    private var workObserver: Observer<List<WorkInfo>>? = null
    private var observedBookId: Int? = null

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
        if (downloadStore.hasPartial(bookId)) {
            val metadata = downloadStore.load(bookId)
            _uiState.value = _uiState.value.copy(
                hasPartialDownload = true,
                isPaused = true,
                bytesDownloaded = downloadStore.partFile(bookId).length(),
                totalBytes = metadata?.totalSize ?: 0L
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

        val book = _uiState.value.book
        val inputData = Data.Builder()
            .putInt(DownloadWorker.KEY_BOOK_ID, bookId)
            .putString(DownloadWorker.KEY_BOOK_TITLE, bookTitle)
            .putString(DownloadWorker.KEY_BOOK_AUTHOR, book?.author ?: "")
            .putLong(DownloadWorker.KEY_BOOK_TOTAL_SIZE, book?.totalSize ?: 0L)
            .putString(DownloadWorker.KEY_DOWNLOAD_DIR, downloadDir)
            .build()

        // No network constraint: WorkManager stops constrained workers on every
        // connectivity blip (screen lock, Wi-Fi handoff). The worker waits for
        // the network itself and keeps its partial data.
        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
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
            downloadStatus = "Starting...",
            isIndeterminate = true,
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
        downloadStore.clear(bookId)

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
        if (observedBookId == bookId && workObserver != null) return
        stopObservingWork()

        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos?.firstOrNull() ?: return@Observer
            onWorkInfo(bookId, workInfo)
        }
        workObserver = observer
        observedBookId = bookId
        workManager.getWorkInfosForUniqueWorkLiveData("download_book_$bookId").observeForever(observer)
    }

    private fun stopObservingWork() {
        val bookId = observedBookId ?: return
        workObserver?.let {
            workManager.getWorkInfosForUniqueWorkLiveData("download_book_$bookId").removeObserver(it)
        }
        workObserver = null
        observedBookId = null
    }

    override fun onCleared() {
        stopObservingWork()
        super.onCleared()
    }

    private fun onWorkInfo(bookId: Int, workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress
                val phase = progress.getString(DownloadWorker.KEY_PHASE)
                val status = progress.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Downloading..."
                val totalBytes = progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L)
                val indeterminate = phase == null ||
                    ((phase == DownloadWorker.PHASE_PREPARING || phase == DownloadWorker.PHASE_WAITING) && totalBytes == 0L)

                _uiState.value = _uiState.value.copy(
                    downloadProgress = progress.getInt(DownloadWorker.KEY_PROGRESS, 0),
                    downloadStatus = status,
                    isDownloading = true,
                    isPaused = false,
                    isIndeterminate = indeterminate,
                    bytesDownloaded = progress.getLong(DownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
                    totalBytes = totalBytes,
                    downloadSpeed = progress.getLong(DownloadWorker.KEY_DOWNLOAD_SPEED, 0L)
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                // The worker records the download itself, so it is tracked even
                // when this screen is not open at completion time.
                _uiState.value = _uiState.value.copy(
                    downloadProgress = 100,
                    downloadStatus = "Download complete",
                    isDownloading = false,
                    isPaused = false,
                    isIndeterminate = false,
                    hasPartialDownload = false,
                    isAlreadyDownloaded = true
                )
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Download failed"
                _uiState.value = _uiState.value.copy(
                    downloadProgress = -1,
                    downloadStatus = error,
                    isDownloading = false,
                    isPaused = false,
                    isIndeterminate = false
                )
                checkForPartialDownload(bookId)
            }
            WorkInfo.State.CANCELLED -> {
                _uiState.value = _uiState.value.copy(
                    downloadProgress = -1,
                    downloadStatus = if (downloadStore.hasPartial(bookId)) "Paused" else "Cancelled",
                    isDownloading = false,
                    isIndeterminate = false
                )
                checkForPartialDownload(bookId)
            }
            WorkInfo.State.ENQUEUED -> {
                val status = if (workInfo.runAttemptCount > 0) {
                    "Interrupted, will resume automatically..."
                } else {
                    "Starting..."
                }
                _uiState.value = _uiState.value.copy(
                    isDownloading = true,
                    downloadStatus = status,
                    isIndeterminate = true,
                    isPaused = false
                )
            }
            WorkInfo.State.BLOCKED -> {
                _uiState.value = _uiState.value.copy(
                    isDownloading = true,
                    downloadStatus = "Waiting...",
                    isIndeterminate = true,
                    isPaused = false
                )
            }
        }
    }
}
