package com.goshelf.app.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goshelf.app.data.api.GoShelfApi
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

data class DownloadMetadata(
    val bookId: Int,
    val bookTitle: String,
    val etag: String,
    val totalSize: Long,
    val bytesDownloaded: Long,
    val outputDir: String,
    val tempFile: String
)

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val api: GoShelfApi
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_BOOK_TITLE = "book_title"
        const val KEY_DOWNLOAD_DIR = "download_dir"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_DOWNLOAD_SPEED = "download_speed"
        private const val TAG = "DownloadWorker"
        private const val CHUNK_SIZE = 2 * 1024 * 1024 // 2MB chunks
        private const val MAX_RETRIES_PER_CHUNK = 10
        private const val INITIAL_RETRY_DELAY_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 60000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

    private val gson = Gson()
    private val notificationHelper = DownloadNotificationHelper(appContext)
    private val notificationId: Int
        get() = DownloadNotificationHelper.NOTIFICATION_ID_BASE + inputData.getInt(KEY_BOOK_ID, 0)

    private fun getDownloadsDir(): File {
        val dir = File(applicationContext.cacheDir, "downloads")
        dir.mkdirs()
        return dir
    }

    private fun getMetadataFile(bookId: Int): File {
        return File(getDownloadsDir(), "book_${bookId}.download")
    }

    private fun getPartFile(bookId: Int): File {
        return File(getDownloadsDir(), "book_${bookId}.part")
    }

    private fun saveMetadata(metadata: DownloadMetadata) {
        val file = getMetadataFile(metadata.bookId)
        file.writeText(gson.toJson(metadata))
    }

    private fun loadMetadata(bookId: Int): DownloadMetadata? {
        val file = getMetadataFile(bookId)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), DownloadMetadata::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read download metadata for book $bookId", e)
            null
        }
    }

    private fun deleteMetadata(bookId: Int) {
        getMetadataFile(bookId).delete()
    }

    private fun deletePartFile(bookId: Int) {
        getPartFile(bookId).delete()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getInt(KEY_BOOK_ID, -1)
        val bookTitle = inputData.getString(KEY_BOOK_TITLE) ?: "Unknown"
        val downloadDir = inputData.getString(KEY_DOWNLOAD_DIR)
            ?: return@withContext Result.failure(workDataOf(KEY_STATUS_MESSAGE to "No download directory specified"))

        if (bookId == -1) {
            return@withContext Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Invalid book ID"))
        }

        try {
            // Set foreground with initial notification
            setForeground(createForegroundInfo(bookTitle, 0, 0L, 0L))

            // Step 1: Get download info from server
            setProgress(workDataOf(
                KEY_PROGRESS to 0,
                KEY_STATUS_MESSAGE to "Getting download info..."
            ))

            val downloadInfo = try {
                api.getDownloadInfo(bookId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get download info for book $bookId", e)
                return@withContext Result.retry()
            }

            // Step 2: Check for existing partial download
            val partFile = getPartFile(bookId)
            var bytesDownloaded: Long = 0
            val existingMetadata = loadMetadata(bookId)

            if (existingMetadata != null && partFile.exists()) {
                if (existingMetadata.etag == downloadInfo.etag) {
                    // Resume from where we left off
                    bytesDownloaded = partFile.length()
                    Log.i(TAG, "Resuming download for book $bookId from $bytesDownloaded bytes")
                } else {
                    // Server file changed, start over
                    Log.i(TAG, "ETag mismatch for book $bookId, starting fresh download")
                    partFile.delete()
                    bytesDownloaded = 0
                }
            } else if (partFile.exists()) {
                // Part file exists but no metadata - delete and start over
                partFile.delete()
            }

            // Step 3: Save metadata
            val sanitizedTitle = bookTitle.replace(Regex("[^a-zA-Z0-9\\s\\-_]"), "").trim()
            val outputDir = File(downloadDir, sanitizedTitle)

            val metadata = DownloadMetadata(
                bookId = bookId,
                bookTitle = bookTitle,
                etag = downloadInfo.etag,
                totalSize = downloadInfo.totalSize,
                bytesDownloaded = bytesDownloaded,
                outputDir = outputDir.absolutePath,
                tempFile = partFile.absolutePath
            )
            saveMetadata(metadata)

            // Step 4: Download with resume support
            val totalSize = downloadInfo.totalSize

            if (bytesDownloaded >= totalSize && totalSize > 0) {
                Log.i(TAG, "Download already complete for book $bookId")
            } else {
                bytesDownloaded = downloadChunked(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    partFile = partFile,
                    startByte = bytesDownloaded,
                    totalSize = totalSize
                )
            }

            // Verify download size
            if (totalSize > 0 && partFile.length() != totalSize) {
                Log.e(TAG, "Download size mismatch: expected $totalSize, got ${partFile.length()}")
                return@withContext Result.retry()
            }

            // Step 5: Extract ZIP
            setProgress(workDataOf(
                KEY_PROGRESS to 90,
                KEY_STATUS_MESSAGE to "Extracting...",
                KEY_BYTES_DOWNLOADED to totalSize,
                KEY_TOTAL_BYTES to totalSize
            ))

            setForeground(createExtractingForegroundInfo(bookTitle))

            outputDir.mkdirs()
            extractZip(partFile, outputDir)

            // Step 6: Clean up temp files
            deletePartFile(bookId)
            deleteMetadata(bookId)

            // Step 7: Update notification to complete
            setProgress(workDataOf(
                KEY_PROGRESS to 100,
                KEY_STATUS_MESSAGE to "Complete",
                KEY_BYTES_DOWNLOADED to totalSize,
                KEY_TOTAL_BYTES to totalSize
            ))

            // Show completion notification
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(
                notificationId + 1000,
                notificationHelper.createCompleteNotification(bookTitle, outputDir.absolutePath).build()
            )

            return@withContext Result.success(workDataOf(
                KEY_OUTPUT_DIR to outputDir.absolutePath,
                KEY_STATUS_MESSAGE to "Downloaded to ${outputDir.absolutePath}"
            ))

        } catch (e: Exception) {
            if (isStopped) {
                // User cancelled - clean up
                Log.i(TAG, "Download cancelled by user for book $bookId")
                deletePartFile(bookId)
                deleteMetadata(bookId)
                return@withContext Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Cancelled"))
            }

            Log.e(TAG, "Download failed for book $bookId", e)

            // Keep partial files for resume - just save current state
            val partFile = getPartFile(bookId)
            if (partFile.exists()) {
                val currentMetadata = loadMetadata(bookId)
                if (currentMetadata != null) {
                    saveMetadata(currentMetadata.copy(bytesDownloaded = partFile.length()))
                }
            }

            // Show error notification
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(
                notificationId + 2000,
                notificationHelper.createErrorNotification(
                    bookTitle,
                    e.message ?: "Unknown error",
                    canRetry = true
                ).build()
            )

            return@withContext Result.retry()
        }
    }

    private suspend fun downloadChunked(
        bookId: Int,
        bookTitle: String,
        partFile: File,
        startByte: Long,
        totalSize: Long
    ): Long {
        var bytesDownloaded = startByte
        var lastProgressUpdate = 0L
        var lastSpeedCalcTime = System.currentTimeMillis()
        var lastSpeedCalcBytes = bytesDownloaded
        var currentSpeed = 0L

        while (bytesDownloaded < totalSize || totalSize == 0L) {
            if (isStopped) {
                // Save progress before stopping
                saveMetadata(DownloadMetadata(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    etag = loadMetadata(bookId)?.etag ?: "",
                    totalSize = totalSize,
                    bytesDownloaded = bytesDownloaded,
                    outputDir = loadMetadata(bookId)?.outputDir ?: "",
                    tempFile = partFile.absolutePath
                ))
                throw IOException("Download stopped by user")
            }

            // Download one chunk with retries
            val chunkBytesWritten = downloadChunkWithRetry(bookId, partFile, bytesDownloaded)

            if (chunkBytesWritten == 0L) {
                // End of stream
                break
            }

            bytesDownloaded += chunkBytesWritten

            // Update metadata
            val currentMetadata = loadMetadata(bookId)
            if (currentMetadata != null) {
                saveMetadata(currentMetadata.copy(bytesDownloaded = bytesDownloaded))
            }

            // Calculate speed
            val now = System.currentTimeMillis()
            val timeDelta = now - lastSpeedCalcTime
            if (timeDelta >= 1000) {
                val bytesDelta = bytesDownloaded - lastSpeedCalcBytes
                currentSpeed = (bytesDelta * 1000) / timeDelta
                lastSpeedCalcTime = now
                lastSpeedCalcBytes = bytesDownloaded
            }

            // Update progress notification (throttled)
            if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                lastProgressUpdate = now
                val progress = if (totalSize > 0) {
                    ((bytesDownloaded * 90) / totalSize).toInt().coerceIn(0, 90)
                } else {
                    0
                }

                setProgress(workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_STATUS_MESSAGE to "Downloading...",
                    KEY_BYTES_DOWNLOADED to bytesDownloaded,
                    KEY_TOTAL_BYTES to totalSize,
                    KEY_DOWNLOAD_SPEED to currentSpeed
                ))

                setForeground(createForegroundInfo(bookTitle, progress, bytesDownloaded, totalSize))
            }
        }

        return bytesDownloaded
    }

    private suspend fun downloadChunkWithRetry(
        bookId: Int,
        partFile: File,
        rangeStart: Long
    ): Long {
        var retryCount = 0
        var delayMs = INITIAL_RETRY_DELAY_MS

        while (true) {
            try {
                return downloadSingleChunk(bookId, partFile, rangeStart)
            } catch (e: IOException) {
                if (isStopped) throw e

                retryCount++
                if (retryCount >= MAX_RETRIES_PER_CHUNK) {
                    Log.e(TAG, "Max retries reached for chunk at offset $rangeStart", e)
                    throw e
                }

                Log.w(TAG, "Chunk download failed at offset $rangeStart (attempt $retryCount/$MAX_RETRIES_PER_CHUNK), retrying in ${delayMs}ms", e)
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)

                // Re-read actual file size in case partial bytes were written
                val actualFileSize = partFile.length()
                if (actualFileSize > rangeStart) {
                    // Some bytes were written before failure - adjust and continue
                    return actualFileSize - rangeStart
                }
            }
        }
    }

    private fun downloadSingleChunk(
        bookId: Int,
        partFile: File,
        rangeStart: Long
    ): Long {
        val (inputStream, contentLength) = api.downloadZipRange(bookId, rangeStart)

        if (contentLength == 0L) {
            inputStream.close()
            return 0L
        }

        val buffer = ByteArray(8192)
        var totalWritten = 0L
        val maxToRead = CHUNK_SIZE.toLong()

        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(rangeStart)
            inputStream.use { input ->
                while (totalWritten < maxToRead) {
                    val toRead = minOf(buffer.size.toLong(), maxToRead - totalWritten).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break

                    raf.write(buffer, 0, read)
                    totalWritten += read
                }
            }
        }

        return totalWritten
    }

    private fun extractZip(zipFile: File, outputDir: File) {
        val buffer = ByteArray(8192)

        // Use ZipFile (random access) instead of ZipInputStream because Go's
        // archive/zip writes Store-method entries with data descriptors (size=0
        // in local header, bit 3 set). Java's ZipInputStream doesn't handle
        // data descriptors correctly for Store method, resulting in 0-byte files.
        // ZipFile reads sizes from the central directory which always has correct values.
        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (isStopped) {
                    throw IOException("Extraction stopped by user")
                }

                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    // Protect against zip path traversal
                    val fileName = File(entry.name).name
                    val outputFile = File(outputDir, fileName)

                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { fos ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    Log.d(TAG, "Extracted: $fileName (${outputFile.length()} bytes)")
                }
            }
        }
    }

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long
    ): ForegroundInfo {
        val notification = notificationHelper.createProgressNotification(
            title = title,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes
        ).build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createExtractingForegroundInfo(title: String): ForegroundInfo {
        val notification = notificationHelper.createExtractingNotification(title).build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
