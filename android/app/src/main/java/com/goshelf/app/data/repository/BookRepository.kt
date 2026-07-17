package com.goshelf.app.data.repository

import com.goshelf.app.data.api.BookDetail
import com.goshelf.app.data.api.BookListItem
import com.goshelf.app.data.api.GoShelfApi
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
}
