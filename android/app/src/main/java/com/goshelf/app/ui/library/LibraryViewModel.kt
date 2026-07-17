package com.goshelf.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.repository.AuthRepository
import com.goshelf.app.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class LibraryUiState(
    val books: List<BookListItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sessionExpired: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var allBooks: List<BookListItem> = emptyList()

    init {
        loadBooks()
    }

    fun loadBooks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                allBooks = bookRepository.getBooks()
                filterBooks()
            } catch (e: IOException) {
                if (e.message == "Not authenticated") {
                    // Session expired - logout and signal UI to navigate back to login
                    authRepository.logout()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        sessionExpired = true
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load library"
                    )
                }
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
        filterBooks()
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
}
