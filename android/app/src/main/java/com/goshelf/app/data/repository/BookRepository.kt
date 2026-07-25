package com.goshelf.app.data.repository

import com.goshelf.app.data.api.APIAuthor
import com.goshelf.app.data.api.APISeries
import com.goshelf.app.data.api.AuthorDetailResponse
import com.goshelf.app.data.api.BookDetail
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.api.SeriesDetailResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookRepository(
    private val api: GoShelfApi,
    private val settingsRepository: SettingsRepository
) {
    suspend fun getBooks(): List<BookListItem> = withContext(Dispatchers.IO) {
        api.getBooks()
    }

    suspend fun getBookDetail(bookId: Int): BookDetail = withContext(Dispatchers.IO) {
        api.getBookDetail(bookId)
    }

    fun getCoverUrl(bookId: Int): String {
        return api.getCoverUrl(bookId)
    }

    fun getDownloadDirectory(): String {
        return settingsRepository.getDownloadDirectory()
    }

    suspend fun getAuthors(): List<APIAuthor> = withContext(Dispatchers.IO) {
        api.getAuthors()
    }

    suspend fun getAuthorDetail(authorId: Int): AuthorDetailResponse = withContext(Dispatchers.IO) {
        api.getAuthorDetail(authorId)
    }

    suspend fun getSeries(): List<APISeries> = withContext(Dispatchers.IO) {
        api.getSeries()
    }

    suspend fun getSeriesDetail(slug: String): SeriesDetailResponse = withContext(Dispatchers.IO) {
        api.getSeriesDetail(slug)
    }
}
