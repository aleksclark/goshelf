package com.goshelf.app.data.repository

import com.goshelf.app.data.local.DownloadedBookDao
import com.goshelf.app.data.local.DownloadedBookEntity
import com.goshelf.app.data.local.StarDao
import com.goshelf.app.data.local.StarEntity

class StarRepository(
    private val starDao: StarDao,
    private val downloadedBookDao: DownloadedBookDao
) {
    suspend fun isStarred(type: String, refId: String): Boolean {
        return starDao.isStarred("$type:$refId")
    }

    suspend fun toggleStar(type: String, refId: String, name: String): Boolean {
        val id = "$type:$refId"
        return if (starDao.isStarred(id)) {
            starDao.delete(id)
            false
        } else {
            starDao.insert(StarEntity(id = id, type = type, refId = refId, name = name))
            true
        }
    }

    suspend fun getStarredBooks(): List<StarEntity> = starDao.getStarsByType("book")
    suspend fun getStarredAuthors(): List<StarEntity> = starDao.getStarsByType("author")
    suspend fun getStarredSeries(): List<StarEntity> = starDao.getStarsByType("series")
    suspend fun getAllStars(): List<StarEntity> = starDao.getAllStars()

    // Download tracking
    suspend fun isDownloaded(bookId: Int): Boolean = downloadedBookDao.isDownloaded(bookId)

    suspend fun markDownloaded(bookId: Int, title: String, author: String, outputDirUri: String, totalSize: Long) {
        downloadedBookDao.insert(
            DownloadedBookEntity(
                bookId = bookId,
                title = title,
                author = author,
                outputDirUri = outputDirUri,
                totalSize = totalSize
            )
        )
    }

    suspend fun removeDownload(bookId: Int) {
        downloadedBookDao.delete(bookId)
    }

    suspend fun getAllDownloads(): List<DownloadedBookEntity> = downloadedBookDao.getAllDownloads()
    suspend fun getDownload(bookId: Int): DownloadedBookEntity? = downloadedBookDao.getDownload(bookId)
}
