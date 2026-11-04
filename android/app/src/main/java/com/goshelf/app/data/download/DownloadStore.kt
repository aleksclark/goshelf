package com.goshelf.app.data.download

import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Persisted state for a partially downloaded book ZIP. [formatVersion] guards
 * against resuming part files written by older app versions, whose server ZIPs
 * did not have a deterministic byte layout.
 */
data class DownloadMetadata(
    val bookId: Int,
    val bookTitle: String,
    val etag: String,
    val totalSize: Long,
    val bytesDownloaded: Long,
    val outputDirUri: String,
    val tempFile: String,
    val formatVersion: Int = DownloadStore.FORMAT_VERSION
)

/**
 * Owns the on-disk layout of in-progress downloads so the worker, view models,
 * and app startup agree on where partial data lives.
 */
class DownloadStore(cacheDir: File) {

    companion object {
        const val FORMAT_VERSION = 2
        private const val TAG = "DownloadStore"
    }

    private val gson = Gson()
    private val dir = File(cacheDir, "downloads")

    private fun ensureDir(): File {
        dir.mkdirs()
        return dir
    }

    fun partFile(bookId: Int): File = File(ensureDir(), "book_${bookId}.part")

    fun metadataFile(bookId: Int): File = File(ensureDir(), "book_${bookId}.download")

    fun load(bookId: Int): DownloadMetadata? {
        val file = metadataFile(bookId)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), DownloadMetadata::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read download metadata for book $bookId", e)
            null
        }
    }

    fun save(metadata: DownloadMetadata) {
        val target = metadataFile(metadata.bookId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(gson.toJson(metadata))
        if (!tmp.renameTo(target)) {
            target.writeText(gson.toJson(metadata))
            tmp.delete()
        }
    }

    fun clear(bookId: Int) {
        partFile(bookId).delete()
        metadataFile(bookId).delete()
    }

    fun hasPartial(bookId: Int): Boolean =
        metadataFile(bookId).exists() && partFile(bookId).exists()

    fun pendingBookIds(): List<Int> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.name.endsWith(".download") }
            ?.mapNotNull { it.name.removePrefix("book_").removeSuffix(".download").toIntOrNull() }
            ?: emptyList()
    }
}
