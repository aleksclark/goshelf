package com.goshelf.app.data.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goshelf.app.data.api.DownloadInfo
import com.goshelf.app.data.api.DownloadInfoResult
import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.api.HttpStatusException
import com.goshelf.app.data.api.NotAuthenticatedException
import com.goshelf.app.data.download.DownloadMetadata
import com.goshelf.app.data.download.DownloadStore
import com.goshelf.app.data.download.NetworkMonitor
import com.goshelf.app.data.download.RangeOutcome
import com.goshelf.app.data.download.SpeedMeter
import com.goshelf.app.data.download.backoffDelayMs
import com.goshelf.app.data.download.bookFolderName
import com.goshelf.app.data.download.classifyRangeResponse
import com.goshelf.app.data.download.parseContentRange
import com.goshelf.app.data.download.percentOf
import com.goshelf.app.data.download.planResume
import com.goshelf.app.data.repository.StarRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Downloads a book ZIP into app cache with byte-exact resume, then extracts it
 * into the user's SAF download folder.
 *
 * The worker runs without a WorkManager network constraint on purpose: a
 * constraint makes WorkManager stop the worker whenever connectivity blips
 * (screen-off Wi-Fi power save, Wi-Fi/cellular handoff, Doze), which used to
 * stall downloads until the screen came back on. Network loss is handled here
 * instead, keeping the foreground service and partial data alive.
 *
 * Partial data is never deleted when the worker is stopped (pause, system
 * stop, process death). Only an explicit user cancel (handled in the UI layer),
 * an ETag/size change on the server, or a checksum failure discards it.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val api: GoShelfApi,
    private val starRepository: StarRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_BOOK_TITLE = "book_title"
        const val KEY_BOOK_AUTHOR = "book_author"
        const val KEY_BOOK_TOTAL_SIZE = "book_total_size"
        const val KEY_DOWNLOAD_DIR = "download_dir"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_DOWNLOAD_SPEED = "download_speed"
        const val KEY_PHASE = "phase"

        const val PHASE_PREPARING = "preparing"
        const val PHASE_WAITING = "waiting"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_EXTRACTING = "extracting"
        const val PHASE_COMPLETE = "complete"

        private const val TAG = "DownloadWorker"
        private const val IO_BUFFER_SIZE = 256 * 1024
        private const val EXTRACT_BUFFER_SIZE = 1024 * 1024
        private const val MAX_CONSECUTIVE_FAILURES = 8
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val NETWORK_WAIT_TIMEOUT_MS = 15 * 60_000L
        private const val PREPARE_TIMEOUT_MS = 30 * 60_000L
        private const val MAX_ZIP_CHANGES = 3
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60_000L

        /**
         * Serializes workers for the same book inside the process. REPLACE
         * enqueues (resume right after pause, re-download) can start a new
         * worker while the previous one is still unwinding; both appending to
         * the same part file would corrupt it.
         */
        private val bookLocks = ConcurrentHashMap<Int, Mutex>()
    }

    private class RetryLater(message: String) : Exception(message)
    private class FailPermanently(message: String) : Exception(message)
    private class ZipChangedException(reason: String) : IOException(reason)
    private class CorruptZipException(message: String) : IOException(message)

    private data class ChildDoc(val uri: Uri, val size: Long, val isDirectory: Boolean)

    private val store = DownloadStore(appContext.cacheDir)
    private val networkMonitor = NetworkMonitor(appContext)
    private val notificationHelper = DownloadNotificationHelper(appContext)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val bookId: Int get() = inputData.getInt(KEY_BOOK_ID, -1)
    private val notificationId: Int
        get() = DownloadNotificationHelper.NOTIFICATION_ID_BASE + inputData.getInt(KEY_BOOK_ID, 0)
    private var lastPublishMs = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = bookId
        val bookTitle = inputData.getString(KEY_BOOK_TITLE) ?: "Unknown"
        val downloadDirUri = inputData.getString(KEY_DOWNLOAD_DIR)
            ?: return@withContext Result.failure(workDataOf(KEY_STATUS_MESSAGE to "No download directory selected. Please choose a folder in Settings."))

        if (bookId == -1) {
            return@withContext Result.failure(workDataOf(KEY_STATUS_MESSAGE to "Invalid book ID"))
        }

        val treeDoc = resolveOutputRoot(downloadDirUri)
        if (treeDoc == null || !treeDoc.canWrite()) {
            return@withContext Result.failure(workDataOf(
                KEY_STATUS_MESSAGE to "Cannot write to download folder. Please re-select in Settings."
            ))
        }

        notificationManager.cancel(notificationId + 2000)
        val wakeLock = acquireWakeLock()
        val wifiLock = acquireWifiLock()
        try {
            promoteToForeground(notificationHelper.createStatusNotification(bookTitle, "Starting...").build())
            bookLocks.getOrPut(bookId) { Mutex() }.withLock {
                runDownload(bookId, bookTitle, downloadDirUri, treeDoc)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download for book $bookId stopped${stopReasonSuffix()}; partial data kept for resume")
            throw e
        } catch (e: FailPermanently) {
            val message = e.message ?: "Download failed"
            Log.e(TAG, "Download for book $bookId failed: $message")
            notifyError(bookTitle, message, canRetry = false)
            Result.failure(workDataOf(KEY_STATUS_MESSAGE to message))
        } catch (e: RetryLater) {
            val message = e.message ?: "Download interrupted"
            Log.w(TAG, "Download for book $bookId will retry later: $message")
            notifyError(bookTitle, message, canRetry = true)
            Result.retry()
        } catch (e: Exception) {
            if (isStopped) {
                Log.i(TAG, "Download for book $bookId stopped${stopReasonSuffix()} during I/O; partial data kept")
                return@withContext Result.retry()
            }
            Log.e(TAG, "Download failed for book $bookId", e)
            notifyError(bookTitle, e.message ?: "Unknown error", canRetry = true)
            Result.retry()
        } finally {
            if (wifiLock?.isHeld == true) wifiLock.release()
            if (wakeLock.isHeld) wakeLock.release()
            notificationManager.cancel(notificationId)
        }
    }

    private suspend fun runDownload(
        bookId: Int,
        bookTitle: String,
        downloadDirUri: String,
        treeDoc: DocumentFile
    ): Result {
        val partFile = store.partFile(bookId)
        var zipChanges = 0
        var info: DownloadInfo

        while (true) {
            info = awaitDownloadInfo(bookId, bookTitle)
            if (info.skippedFiles > 0) {
                Log.w(TAG, "Book $bookId: server skipped ${info.skippedFiles} unreadable file(s)")
            }

            val partLength = if (partFile.exists()) partFile.length() else 0L
            val offset = planResume(store.load(bookId), partLength, info.etag, info.totalSize)
            if (offset == 0L) {
                if (partLength > 0L) Log.i(TAG, "Book $bookId: discarding $partLength stale partial bytes")
                partFile.delete()
            } else {
                Log.i(TAG, "Book $bookId: resuming at $offset of ${info.totalSize} bytes")
            }
            store.save(DownloadMetadata(
                bookId = bookId,
                bookTitle = bookTitle,
                etag = info.etag,
                totalSize = info.totalSize,
                bytesDownloaded = offset,
                outputDirUri = downloadDirUri,
                tempFile = partFile.absolutePath
            ))

            try {
                downloadToPart(bookId, bookTitle, partFile, offset, info)
                break
            } catch (e: ZipChangedException) {
                Log.w(TAG, "Book $bookId: server ZIP changed (${e.message}); restarting download")
                store.clear(bookId)
                zipChanges++
                if (zipChanges >= MAX_ZIP_CHANGES) {
                    throw RetryLater("Server file keeps changing")
                }
            }
        }

        if (partFile.length() != info.totalSize) {
            Log.e(TAG, "Book $bookId: size mismatch, expected ${info.totalSize}, got ${partFile.length()}")
            store.clear(bookId)
            throw RetryLater("Downloaded size did not match")
        }

        val folderName = bookFolderName(bookTitle, bookId)
        val bookDir = treeDoc.findFile(folderName)?.takeIf { it.isDirectory }
            ?: treeDoc.createDirectory(folderName)
            ?: throw FailPermanently("Failed to create directory: $folderName")

        try {
            extractZipToSaf(partFile, bookDir, bookTitle)
        } catch (e: CorruptZipException) {
            Log.e(TAG, "Book $bookId: ${e.message}; discarding download")
            store.clear(bookId)
            throw RetryLater("Downloaded file was corrupt and will be downloaded again")
        } catch (e: ZipException) {
            Log.e(TAG, "Book $bookId: invalid ZIP; discarding download", e)
            store.clear(bookId)
            throw RetryLater("Downloaded file was corrupt and will be downloaded again")
        }

        store.clear(bookId)
        val outputDir = bookDir.uri.toString()
        try {
            starRepository.markDownloaded(
                bookId = bookId,
                title = bookTitle,
                author = inputData.getString(KEY_BOOK_AUTHOR) ?: "",
                outputDirUri = outputDir,
                totalSize = inputData.getLong(KEY_BOOK_TOTAL_SIZE, info.totalSize)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Book $bookId: failed to record download", e)
        }

        setProgress(workDataOf(
            KEY_PHASE to PHASE_COMPLETE,
            KEY_PROGRESS to 100,
            KEY_STATUS_MESSAGE to "Complete",
            KEY_BYTES_DOWNLOADED to info.totalSize,
            KEY_TOTAL_BYTES to info.totalSize
        ))
        notificationManager.notify(
            notificationId + 1000,
            notificationHelper.createCompleteNotification(bookTitle, folderName).build()
        )

        return Result.success(workDataOf(
            KEY_OUTPUT_DIR to outputDir,
            KEY_STATUS_MESSAGE to "Downloaded: $folderName"
        ))
    }

    /** Polls download-info until the server has the ZIP ready. */
    private suspend fun awaitDownloadInfo(bookId: Int, title: String): DownloadInfo {
        val deadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS
        var failures = 0
        while (true) {
            awaitNetwork(title)
            publishStatus(PHASE_PREPARING, "Preparing download on server...", title, force = true)
            try {
                when (val result = api.getDownloadInfo(bookId)) {
                    is DownloadInfoResult.Ready -> return result.info
                    is DownloadInfoResult.Preparing -> {
                        failures = 0
                        if (SystemClock.elapsedRealtime() > deadline) {
                            throw RetryLater("Server is still preparing the download")
                        }
                        delay(result.retryAfterSeconds.coerceIn(2, 15) * 1000L)
                    }
                }
            } catch (e: NotAuthenticatedException) {
                throw FailPermanently("Session expired. Please log in again.")
            } catch (e: HttpStatusException) {
                failures = backoffAfterFailure(failures, e, title, permanent = !e.isRetryable)
            } catch (e: IOException) {
                failures = backoffAfterFailure(failures, e, title, permanent = false)
            }
        }
    }

    private suspend fun downloadToPart(
        bookId: Int,
        title: String,
        partFile: File,
        startOffset: Long,
        info: DownloadInfo
    ) {
        var offset = startOffset
        var failures = 0
        val speed = SpeedMeter(offset, SystemClock.elapsedRealtime())
        publishDownload(title, offset, info.totalSize, 0L, force = true)

        while (offset < info.totalSize) {
            if (isStopped) throw CancellationException("Worker stopped")
            awaitNetwork(title)
            val before = offset
            try {
                offset = streamRange(bookId, title, partFile, offset, info, speed)
                if (offset == before) {
                    failures = backoffAfterFailure(failures, IOException("No data received"), title, permanent = false)
                } else {
                    failures = 0
                }
            } catch (e: ZipChangedException) {
                throw e
            } catch (e: NotAuthenticatedException) {
                throw FailPermanently("Session expired. Please log in again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isStopped) throw CancellationException("Worker stopped")
                offset = partFile.length().coerceAtMost(info.totalSize)
                if (offset > before) failures = 0
                val permanent = e is HttpStatusException && !e.isRetryable
                Log.w(TAG, "Book $bookId: transfer interrupted at $offset/${info.totalSize}: ${e.message}")
                failures = backoffAfterFailure(failures, e, title, permanent)
            }
        }
    }

    /**
     * Performs one HTTP request from [offset] and appends what arrives.
     * Returns the new offset. Received bytes are durable in the part file even
     * if the connection drops, so the caller resumes from the file length.
     */
    private suspend fun streamRange(
        bookId: Int,
        title: String,
        partFile: File,
        offset: Long,
        info: DownloadInfo,
        speed: SpeedMeter
    ): Long = coroutineScope {
        alignPartFile(partFile, offset)
        val stream = api.openZipStream(bookId, offset, if (offset > 0L) info.etag else null)
        // Abort a blocked socket read as soon as the worker is stopped, so a
        // replacement worker never races this one on the part file.
        val watchdog = launch {
            try {
                awaitCancellation()
            } finally {
                stream.cancel()
            }
        }
        try {
            stream.use {
                val outcome = classifyRangeResponse(
                    code = stream.code,
                    contentRange = parseContentRange(stream.contentRange),
                    responseEtag = stream.etag,
                    contentLength = stream.contentLength,
                    requestedOffset = offset,
                    expectedEtag = info.etag,
                    expectedTotal = info.totalSize
                )
                when (outcome) {
                    RangeOutcome.Append -> Unit
                    RangeOutcome.Complete -> return@coroutineScope offset
                    is RangeOutcome.ZipChanged -> throw ZipChangedException(outcome.reason)
                }

                var position = offset
                val buffer = ByteArray(IO_BUFFER_SIZE)
                FileOutputStream(partFile, true).use { out ->
                    while (position < info.totalSize) {
                        val read = stream.body.read(buffer)
                        if (read == -1) break
                        if (isStopped) throw CancellationException("Worker stopped")
                        val writable = minOf(read.toLong(), info.totalSize - position).toInt()
                        out.write(buffer, 0, writable)
                        position += writable
                        val now = SystemClock.elapsedRealtime()
                        publishDownload(title, position, info.totalSize, speed.update(position, now))
                    }
                }
                position
            }
        } finally {
            watchdog.cancel()
        }
    }

    private fun alignPartFile(partFile: File, offset: Long) {
        val length = if (partFile.exists()) partFile.length() else 0L
        when {
            length == offset -> Unit
            length > offset -> RandomAccessFile(partFile, "rw").use { it.setLength(offset) }
            else -> throw ZipChangedException("partial data missing")
        }
    }

    /**
     * Extracts with large buffered writes, one child listing for the whole
     * folder, CRC verification, and byte-level progress. Files already present
     * with the right size (from an interrupted extraction) are skipped.
     */
    private suspend fun extractZipToSaf(zipFile: File, outputDir: DocumentFile, title: String) {
        val resolver = applicationContext.contentResolver
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val totalBytes = entries.sumOf { it.size.coerceAtLeast(0L) }
            val existing = listChildren(outputDir)
            val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
            val crc = CRC32()
            var done = 0L
            val speed = SpeedMeter(0L, SystemClock.elapsedRealtime())
            publishExtract(title, 0, entries.size, 0L, totalBytes, 0L, force = true)

            entries.forEachIndexed { index, entry ->
                if (isStopped) throw CancellationException("Worker stopped")
                val fileName = File(entry.name).name
                if (fileName.isBlank()) return@forEachIndexed

                val present = existing[fileName]
                if (present != null && !present.isDirectory && entry.size >= 0 && present.size == entry.size) {
                    done += entry.size
                    publishExtract(title, index + 1, entries.size, done, totalBytes, speed.update(done, SystemClock.elapsedRealtime()))
                    return@forEachIndexed
                }
                if (present != null && !present.isDirectory) {
                    deleteDocument(present.uri)
                }

                val outUri = createOutputFile(outputDir, fileName)

                crc.reset()
                zip.getInputStream(entry).use { input ->
                    val output = resolver.openOutputStream(outUri, "w")
                        ?: throw IOException("Failed to open output stream for: $fileName")
                    output.use {
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            done += read
                            if (isStopped) throw CancellationException("Worker stopped")
                            publishExtract(title, index, entries.size, done, totalBytes, speed.update(done, SystemClock.elapsedRealtime()))
                        }
                    }
                }
                if (entry.crc != -1L && crc.value != entry.crc) {
                    deleteDocument(outUri)
                    throw CorruptZipException("Checksum mismatch in $fileName")
                }
            }
            publishExtract(title, entries.size, entries.size, totalBytes, totalBytes, 0L, force = true)
        }
    }

    /** SAF tree URIs come from the folder picker; file:// URIs are accepted for app-private folders. */
    private fun resolveOutputRoot(uriString: String): DocumentFile? {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return DocumentFile.fromFile(File(path))
        }
        return DocumentFile.fromTreeUri(applicationContext, uri)
    }

    /** Lists a SAF folder with a single query (DocumentFile.findFile re-lists the folder on every call). */
    private fun listChildren(dir: DocumentFile): Map<String, ChildDoc> {
        val resolver = applicationContext.contentResolver
        val result = mutableMapOf<String, ChildDoc>()
        if (dir.uri.scheme == "file") {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                result[name] = ChildDoc(child.uri, child.length(), child.isDirectory)
            }
            return result
        }
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, DocumentsContract.getDocumentId(dir.uri))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot list ${dir.uri}", e)
            return result
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val size = if (cursor.isNull(2)) -1L else cursor.getLong(2)
                val isDir = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                result[name] = ChildDoc(
                    uri = DocumentsContract.buildDocumentUriUsingTree(dir.uri, docId),
                    size = size,
                    isDirectory = isDir
                )
            }
        }
        return result
    }

    private fun createOutputFile(dir: DocumentFile, fileName: String): Uri {
        if (dir.uri.scheme == "file") {
            val file = File(dir.uri.path ?: throw IOException("Invalid output folder"), fileName)
            if (!file.createNewFile() && !file.isFile) throw IOException("Failed to create file: $fileName")
            return Uri.fromFile(file)
        }
        return dir.createFile(mimeTypeFor(fileName), fileName)?.uri
            ?: throw IOException("Failed to create file: $fileName")
    }

    private fun deleteDocument(uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
        } else {
            DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)
        }
    }

    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        fileName.endsWith(".m4b", ignoreCase = true) -> "audio/mp4"
        fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        fileName.endsWith(".opus", ignoreCase = true) -> "audio/opus"
        fileName.endsWith(".wma", ignoreCase = true) -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }

    private suspend fun awaitNetwork(title: String) {
        if (networkMonitor.isAvailable()) return
        Log.i(TAG, "Book $bookId: waiting for network")
        publishStatus(PHASE_WAITING, "Waiting for network...", title, force = true)
        withTimeoutOrNull(NETWORK_WAIT_TIMEOUT_MS) { networkMonitor.awaitAvailable() }
            ?: throw RetryLater("No network connection")
    }

    /**
     * Sleeps with exponential backoff after a failed attempt and returns the
     * new consecutive failure count. Gives up after MAX_CONSECUTIVE_FAILURES,
     * either permanently or by handing the work back to WorkManager.
     */
    private suspend fun backoffAfterFailure(failures: Int, error: Exception, title: String, permanent: Boolean): Int {
        if (isStopped) throw CancellationException("Worker stopped")
        val attempt = failures + 1
        if (attempt >= MAX_CONSECUTIVE_FAILURES) {
            val message = error.message ?: "Network error"
            if (permanent) throw FailPermanently(message)
            throw RetryLater(message)
        }
        val delayMs = backoffDelayMs(attempt, INITIAL_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Book $bookId: attempt $attempt failed (${error.message}); retrying in ${delayMs}ms")
        publishStatus(PHASE_WAITING, "Connection problem, retrying in ${delayMs / 1000}s...", title, force = true)
        delay(delayMs)
        return attempt
    }

    private suspend fun publishStatus(phase: String, status: String, title: String, force: Boolean = false) {
        if (!shouldPublish(force)) return
        val metadata = store.load(bookId)
        val done = if (metadata != null && store.partFile(bookId).exists()) store.partFile(bookId).length() else 0L
        val total = metadata?.totalSize ?: 0L
        setProgress(workDataOf(
            KEY_PHASE to phase,
            KEY_STATUS_MESSAGE to status,
            KEY_PROGRESS to percentOf(done, total),
            KEY_BYTES_DOWNLOADED to done,
            KEY_TOTAL_BYTES to total,
            KEY_DOWNLOAD_SPEED to 0L
        ))
        showNotification(notificationHelper.createStatusNotification(title, status).build())
    }

    private suspend fun publishDownload(title: String, done: Long, total: Long, speed: Long, force: Boolean = false) {
        if (!shouldPublish(force)) return
        val progress = percentOf(done, total)
        setProgress(workDataOf(
            KEY_PHASE to PHASE_DOWNLOADING,
            KEY_STATUS_MESSAGE to "Downloading...",
            KEY_PROGRESS to progress,
            KEY_BYTES_DOWNLOADED to done,
            KEY_TOTAL_BYTES to total,
            KEY_DOWNLOAD_SPEED to speed
        ))
        showNotification(notificationHelper.createProgressNotification(title, progress, done, total).build())
    }

    private suspend fun publishExtract(
        title: String,
        filesDone: Int,
        fileCount: Int,
        done: Long,
        total: Long,
        speed: Long,
        force: Boolean = false
    ) {
        if (!shouldPublish(force)) return
        val progress = percentOf(done, total)
        val current = (filesDone + 1).coerceAtMost(fileCount)
        setProgress(workDataOf(
            KEY_PHASE to PHASE_EXTRACTING,
            KEY_STATUS_MESSAGE to "Extracting file $current of $fileCount...",
            KEY_PROGRESS to progress,
            KEY_BYTES_DOWNLOADED to done,
            KEY_TOTAL_BYTES to total,
            KEY_DOWNLOAD_SPEED to speed
        ))
        showNotification(notificationHelper.createExtractingNotification(title, progress, filesDone, fileCount).build())
    }

    private fun shouldPublish(force: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMs < PROGRESS_UPDATE_INTERVAL_MS) return false
        lastPublishMs = now
        return true
    }

    private fun showNotification(notification: Notification) {
        try {
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post download notification", e)
        }
    }

    private fun notifyError(title: String, message: String, canRetry: Boolean) {
        try {
            notificationManager.notify(
                notificationId + 2000,
                notificationHelper.createErrorNotification(title, message, canRetry).build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post error notification", e)
        }
    }

    /**
     * Runs as a dataSync foreground service when allowed. Android 12+ refuses
     * to start one while the app is in the background (e.g. a WorkManager
     * retry); the download then continues as regular background work instead
     * of failing.
     */
    private suspend fun promoteToForeground(notification: Notification) {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Book $bookId: cannot start foreground service; continuing in background", e)
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock {
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoShelf:download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock(): WifiManager.WifiLock? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return try {
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GoShelf:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot acquire Wi-Fi lock", e)
            null
        }
    }

    private fun stopReasonSuffix(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " (stop reason $stopReason)" else ""
}
