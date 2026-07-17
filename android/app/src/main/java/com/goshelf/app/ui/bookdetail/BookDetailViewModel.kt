package com.goshelf.app.ui.bookdetail

import android.content.Context
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
import javax.inject.Inject

data class BookDetailUiState(
    val book: BookDetail? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloadProgress: Int = -1,
    val downloadStatus: String? = null,
    val isDownloading: Boolean = false
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val workManager = WorkManager.getInstance(context)

    fun loadBook(bookId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val book = bookRepository.getBookDetail(bookId)
                _uiState.value = _uiState.value.copy(book = book, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load book details"
                )
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
            .build()

        workManager.enqueueUniqueWork(
            "download_book_$bookId",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        _uiState.value = _uiState.value.copy(isDownloading = true, downloadProgress = 0)

        // Observe work progress
        workManager.getWorkInfoByIdLiveData(downloadRequest.id).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                    val status = workInfo.progress.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Downloading..."
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = progress,
                        downloadStatus = status,
                        isDownloading = true
                    )
                }
                WorkInfo.State.SUCCEEDED -> {
                    val outputDir = workInfo.outputData.getString(DownloadWorker.KEY_OUTPUT_DIR) ?: ""
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = 100,
                        downloadStatus = "Downloaded to $outputDir",
                        isDownloading = false
                    )
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString(DownloadWorker.KEY_STATUS_MESSAGE) ?: "Download failed"
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = -1,
                        downloadStatus = error,
                        isDownloading = false
                    )
                }
                WorkInfo.State.CANCELLED -> {
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = -1,
                        downloadStatus = "Cancelled",
                        isDownloading = false
                    )
                }
                else -> {}
            }
        }
    }
}
