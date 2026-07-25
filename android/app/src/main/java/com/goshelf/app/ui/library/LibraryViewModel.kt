package com.goshelf.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goshelf.app.data.api.APIAuthor
import com.goshelf.app.data.api.APISeries
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.local.DownloadedBookEntity
import com.goshelf.app.data.repository.AuthRepository
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class LibraryUiState(
    val books: List<BookListItem> = emptyList(),
    val authors: List<APIAuthor> = emptyList(),
    val seriesList: List<APISeries> = emptyList(),
    val starredBooks: List<BookListItem> = emptyList(),
    val starredAuthors: List<APIAuthor> = emptyList(),
    val starredSeries: List<APISeries> = emptyList(),
    val downloadedBooks: List<DownloadedBookEntity> = emptyList(),
    val downloadedBookIds: Set<Int> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sessionExpired: Boolean = false,
    val selectedTab: LibraryTab = LibraryTab.AUTHORS
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val authRepository: AuthRepository,
    private val starRepository: StarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var allBooks: List<BookListItem> = emptyList()
    private var allAuthors: List<APIAuthor> = emptyList()
    private var allSeries: List<APISeries> = emptyList()

    init {
        loadCurrentTab()
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, error = null)
        loadCurrentTab()
    }

    fun loadCurrentTab() {
        when (_uiState.value.selectedTab) {
            LibraryTab.AUTHORS -> loadAuthors()
            LibraryTab.SERIES -> loadSeries()
            LibraryTab.STARRED -> loadStarred()
            LibraryTab.DOWNLOADED -> loadDownloaded()
        }
    }

    private fun loadAuthors() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                allAuthors = bookRepository.getAuthors()
                filterAuthors()
            } catch (e: IOException) {
                handleError(e)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load authors"
                )
            }
        }
    }

    private fun loadSeries() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                allSeries = bookRepository.getSeries()
                filterSeries()
            } catch (e: IOException) {
                handleError(e)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load series"
                )
            }
        }
    }

    private fun loadStarred() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Load all books if not loaded yet (needed to resolve starred book IDs)
                if (allBooks.isEmpty()) {
                    allBooks = bookRepository.getBooks()
                }
                if (allAuthors.isEmpty()) {
                    allAuthors = bookRepository.getAuthors()
                }
                if (allSeries.isEmpty()) {
                    allSeries = bookRepository.getSeries()
                }

                val starredBookEntities = starRepository.getStarredBooks()
                val starredAuthorEntities = starRepository.getStarredAuthors()
                val starredSeriesEntities = starRepository.getStarredSeries()

                val starredBookIds = starredBookEntities.map { it.refId.toIntOrNull() }.filterNotNull().toSet()
                val starredAuthorIds = starredAuthorEntities.map { it.refId.toIntOrNull() }.filterNotNull().toSet()
                val starredSeriesSlugs = starredSeriesEntities.map { it.refId }.toSet()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    starredBooks = allBooks.filter { it.id in starredBookIds },
                    starredAuthors = allAuthors.filter { it.id in starredAuthorIds },
                    starredSeries = allSeries.filter { it.slug in starredSeriesSlugs }
                )
            } catch (e: IOException) {
                handleError(e)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load starred items"
                )
            }
        }
    }

    private fun loadDownloaded() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val downloads = starRepository.getAllDownloads()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    downloadedBooks = downloads,
                    downloadedBookIds = downloads.map { it.bookId }.toSet()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load downloads"
                )
            }
        }
    }

    fun loadBooks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                allBooks = bookRepository.getBooks()
                filterBooks()
            } catch (e: IOException) {
                handleError(e)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load library"
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        when (_uiState.value.selectedTab) {
            LibraryTab.AUTHORS -> filterAuthors()
            LibraryTab.SERIES -> filterSeries()
            else -> {}
        }
    }

    private fun filterAuthors() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) allAuthors
        else allAuthors.filter { it.name.lowercase().contains(query) }
        _uiState.value = _uiState.value.copy(authors = filtered, isLoading = false)
    }

    private fun filterSeries() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) allSeries
        else allSeries.filter { it.name.lowercase().contains(query) }
        _uiState.value = _uiState.value.copy(seriesList = filtered, isLoading = false)
    }

    private fun filterBooks() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) {
            allBooks
        } else {
            allBooks.filter {
                it.title.lowercase().contains(query) ||
                it.author.lowercase().contains(query) ||
                (it.series?.lowercase()?.contains(query) == true)
            }
        }
        _uiState.value = _uiState.value.copy(books = filtered, isLoading = false)
    }

    fun getCoverUrl(bookId: Int): String {
        return bookRepository.getCoverUrl(bookId)
    }

    fun logout() {
        authRepository.logout()
    }

    private fun handleError(e: IOException) {
        if (e.message == "Not authenticated") {
            authRepository.logout()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                sessionExpired = true
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message ?: "Network error"
            )
        }
    }
}
