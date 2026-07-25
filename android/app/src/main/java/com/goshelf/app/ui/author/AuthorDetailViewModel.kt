package com.goshelf.app.ui.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goshelf.app.data.api.APISeries
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthorDetailUiState(
    val authorName: String = "",
    val authorId: Int = 0,
    val series: List<APISeries> = emptyList(),
    val standaloneBooks: List<BookListItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isStarred: Boolean = false
)

@HiltViewModel
class AuthorDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val starRepository: StarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorDetailUiState())
    val uiState: StateFlow<AuthorDetailUiState> = _uiState.asStateFlow()

    fun loadAuthor(authorId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, authorId = authorId)
            try {
                val detail = bookRepository.getAuthorDetail(authorId)
                val isStarred = starRepository.isStarred("author", authorId.toString())
                _uiState.value = _uiState.value.copy(
                    authorName = detail.author.name,
                    series = detail.series,
                    standaloneBooks = detail.books,
                    isLoading = false,
                    isStarred = isStarred
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load author"
                )
            }
        }
    }

    fun toggleStar() {
        viewModelScope.launch {
            val state = _uiState.value
            val isNowStarred = starRepository.toggleStar(
                "author", state.authorId.toString(), state.authorName
            )
            _uiState.value = state.copy(isStarred = isNowStarred)
        }
    }

    fun getCoverUrl(bookId: Int): String = bookRepository.getCoverUrl(bookId)
}
