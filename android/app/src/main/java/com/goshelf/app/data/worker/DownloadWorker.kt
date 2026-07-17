package com.goshelf.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goshelf.app.data.api.GoShelfApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

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
        private const val TAG = "DownloadWorker"
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getInt(KEY_BOOK_ID, -1)
        val bookTitle = inputData.getString(KEY_BOOK_TITLE) ?: "Unknown"
        val downloadDir = inputData.getString(KEY_DOWNLOAD_DIR)
            ?: return Result.failure(workDataOf(KEY_STATUS_MESSAGE to "No download directory specified"))

        if (bookId == -1) {
            return Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Invalid book ID"))
        }

        try {
            setProgress(workDataOf(
                KEY_PROGRESS to 0,
                KEY_STATUS_MESSAGE to "Downloading..."
            ))

            // Create output directory
            val sanitizedTitle = bookTitle.replace(Regex("[^a-zA-Z0-9\\s\\-_]"), "").trim()
            val outputDir = File(downloadDir, sanitizedTitle)
            outputDir.mkdirs()

            // Download the ZIP
            val (inputStream, contentLength) = api.downloadZip(bookId)
            val tempFile = File(applicationContext.cacheDir, "download_${bookId}.zip")

            var bytesRead: Long = 0
            val buffer = ByteArray(8192)

            FileOutputStream(tempFile).use { fos ->
                inputStream.use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isStopped) {
                            tempFile.delete()
                            return Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Cancelled"))
                        }
                        fos.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val progress = ((bytesRead * 50) / contentLength).toInt()
                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_STATUS_MESSAGE to "Downloading... ${bytesRead / 1024 / 1024}MB"
                            ))
                        }
                    }
                }
            }

            setProgress(workDataOf(
                KEY_PROGRESS to 50,
                KEY_STATUS_MESSAGE to "Extracting..."
            ))

            // Extract ZIP
            val totalBytes = tempFile.length()
            var extractedBytes: Long = 0

            ZipInputStream(BufferedInputStream(tempFile.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (isStopped) {
                        tempFile.delete()
                        return Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Cancelled"))
                    }

                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name
                        val outputFile = File(outputDir, fileName)

                        FileOutputStream(outputFile).use { fos ->
                            var read: Int
                            while (zip.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                                extractedBytes += read
                            }
                        }

                        if (totalBytes > 0) {
                            val progress = 50 + ((extractedBytes * 50) / totalBytes).toInt().coerceAtMost(49)
                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_STATUS_MESSAGE to "Extracting: $fileName"
                            ))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // Clean up temp file
            tempFile.delete()

            setProgress(workDataOf(
                KEY_PROGRESS to 100,
                KEY_STATUS_MESSAGE to "Complete"
            ))

            return Result.success(workDataOf(
                KEY_OUTPUT_DIR to outputDir.absolutePath,
                KEY_STATUS_MESSAGE to "Downloaded to ${outputDir.absolutePath}"
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for book $bookId", e)
            return Result.failure(workDataOf(
                KEY_STATUS_MESSAGE to "Failed: ${e.message}"
            ))
        }
    }
}
