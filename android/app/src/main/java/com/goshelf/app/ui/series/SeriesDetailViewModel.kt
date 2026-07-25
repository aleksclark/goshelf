package com.goshelf.app.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeriesDetailUiState(
    val seriesName: String = "",
    val slug: String = "",
    val books: List<BookListItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isStarred: Boolean = false
)

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val starRepository: StarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    fun loadSeries(slug: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, slug = slug)
            try {
                val detail = bookRepository.getSeriesDetail(slug)
                val isStarred = starRepository.isStarred("series", slug)
                _uiState.value = _uiState.value.copy(
                    seriesName = detail.name,
                    books = detail.books,
                    isLoading = false,
                    isStarred = isStarred
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load series"
                )
            }
        }
    }

    fun toggleStar() {
        viewModelScope.launch {
            val state = _uiState.value
            val isNowStarred = starRepository.toggleStar(
                "series", state.slug, state.seriesName
            )
            _uiState.value = state.copy(isStarred = isNowStarred)
        }
    }

    fun getCoverUrl(bookId: Int): String = bookRepository.getCoverUrl(bookId)
}
