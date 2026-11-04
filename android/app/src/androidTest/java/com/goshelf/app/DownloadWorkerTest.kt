package com.goshelf.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.download.DownloadMetadata
import com.goshelf.app.data.download.DownloadStore
import com.goshelf.app.data.local.GoShelfDatabase
import com.goshelf.app.data.repository.SettingsRepository
import com.goshelf.app.data.repository.StarRepository
import com.goshelf.app.data.worker.DownloadWorker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Runs the real DownloadWorker against a MockWebServer that implements the
 * server's download protocol (async download-info, Range + If-Range, ETag).
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var api: GoShelfApi
    private lateinit var database: GoShelfDatabase
    private lateinit var starRepository: StarRepository
    private lateinit var store: DownloadStore
    private lateinit var outputRoot: File

    private val bookId = 4242
    private val files = linkedMapOf(
        "ch01.mp3" to Random(1).nextBytes(300_000),
        "ch02.mp3" to Random(2).nextBytes(250_000),
        "cover.jpg" to Random(3).nextBytes(20_000)
    )
    private lateinit var zipBytes: ByteArray
    @Volatile
    private var etag = "\"etag-v1\""

    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val preparingPolls = AtomicInteger(0)
    private val disconnectFirstDownloadAt = AtomicInteger(-1)
    @Volatile
    private var downloadInfoStatus = 200

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        zipBytes = buildZip(files)
        store = DownloadStore(context.cacheDir)
        store.clear(bookId)
        outputRoot = File(context.cacheDir, "download_worker_test_out").apply {
            deleteRecursively()
            mkdirs()
        }

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    path.startsWith("/api/books/$bookId/download-info") -> downloadInfo()
                    path == "/books/$bookId/download.zip" -> download(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val prefs = context.getSharedPreferences("download_worker_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = SettingsRepository(prefs)
        settings.setServerUrl(server.url("/").toString().trimEnd('/'))
        api = GoShelfApi(OkHttpClient.Builder().followRedirects(false).build(), settings)

        database = Room.inMemoryDatabaseBuilder(context, GoShelfDatabase::class.java).build()
        starRepository = StarRepository(database.starDao(), database.downloadedBookDao())
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        store.clear(bookId)
        outputRoot.deleteRecursively()
    }

    private fun downloadInfo(reportedEtag: String = etag): MockResponse {
        if (downloadInfoStatus != 200) return MockResponse().setResponseCode(downloadInfoStatus)
        if (preparingPolls.getAndDecrement() > 0) {
            return MockResponse()
                .setResponseCode(202)
                .setHeader("Retry-After", "1")
                .setBody("""{"status":"preparing","retryAfter":1}""")
        }
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"status":"ready","totalSize":${zipBytes.size},"etag":${jsonString(reportedEtag)},"filename":"Test.zip","fileCount":${files.size}}""")
    }

    private fun download(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        val ifRange = request.getHeader("If-Range")
        if (range != null && (ifRange == null || ifRange == etag)) {
            val start = range.removePrefix("bytes=").substringBefore("-").toInt()
            if (start >= zipBytes.size) {
                return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${zipBytes.size}")
            }
            return MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes $start-${zipBytes.size - 1}/${zipBytes.size}")
                .setBody(Buffer().write(zipBytes, start, zipBytes.size - start))
        }

        val cut = disconnectFirstDownloadAt.getAndSet(-1)
        if (cut > 0) {
            return MockResponse()
                .setHeader("ETag", etag)
                .setBody(Buffer().write(zipBytes, 0, cut))
                .setHeader("Content-Length", zipBytes.size.toString())
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        return MockResponse()
            .setHeader("ETag", etag)
            .setBody(Buffer().write(zipBytes))
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(
                DownloadWorker.KEY_BOOK_ID to bookId,
                DownloadWorker.KEY_BOOK_TITLE to "Test: Book",
                DownloadWorker.KEY_BOOK_AUTHOR to "Author",
                DownloadWorker.KEY_BOOK_TOTAL_SIZE to 570_000L,
                DownloadWorker.KEY_DOWNLOAD_DIR to Uri.fromFile(outputRoot).toString()
            ))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = DownloadWorker(appContext, workerParameters, api, starRepository)
            })
            .build()
        worker.doWork()
    }

    private fun downloadRequests() = requests.filter { it.path == "/books/$bookId/download.zip" }

    private fun assertExtracted() {
        val bookDir = File(outputRoot, "Test Book")
        for ((name, data) in files) {
            val out = File(bookDir, name)
            assertTrue("$name should be extracted", out.isFile)
            assertArrayEquals("$name content", data, out.readBytes())
        }
    }

    private fun seedPartial(bytes: ByteArray, etag: String = this.etag, formatVersion: Int = DownloadStore.FORMAT_VERSION) {
        store.partFile(bookId).writeBytes(bytes)
        store.save(DownloadMetadata(
            bookId = bookId,
            bookTitle = "Test: Book",
            etag = etag,
            totalSize = zipBytes.size.toLong(),
            bytesDownloaded = bytes.size.toLong(),
            outputDirUri = Uri.fromFile(outputRoot).toString(),
            tempFile = store.partFile(bookId).absolutePath,
            formatVersion = formatVersion
        ))
    }

    @Test
    fun pollsWhilePreparingResumesAfterDisconnectAndExtracts() = runBlocking<Unit> {
        preparingPolls.set(2)
        disconnectFirstDownloadAt.set(200_000)

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        assertTrue("download-info should be polled while preparing",
            requests.count { it.path?.startsWith("/api/books/$bookId/download-info") == true } >= 3)

        val downloads = downloadRequests()
        assertNull("first request is a full download", downloads.first().getHeader("Range"))
        val resume = downloads.last()
        assertEquals("resume continues from the bytes received before the drop",
            "bytes=200000-", resume.getHeader("Range"))
        assertEquals(etag, resume.getHeader("If-Range"))

        assertFalse("partial data cleared", store.hasPartial(bookId))
        assertTrue("download recorded by worker", starRepository.isDownloaded(bookId))
    }

    @Test
    fun resumesExistingPartialWithRangeAndIfRange() {
        seedPartial(zipBytes.copyOfRange(0, 123_456))

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        val first = downloadRequests().first()
        assertEquals("bytes=123456-", first.getHeader("Range"))
        assertEquals(etag, first.getHeader("If-Range"))
        assertEquals(1, downloadRequests().size)
    }

    @Test
    fun completePartialSkipsDownloadAndOnlyExtracts() {
        seedPartial(zipBytes)

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        assertTrue("no download request needed", downloadRequests().isEmpty())
    }

    @Test
    fun partialForDifferentEtagIsDiscarded() {
        seedPartial(zipBytes.copyOfRange(0, 50_000), etag = "\"etag-old\"")

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        assertNull(downloadRequests().first().getHeader("Range"))
    }

    @Test
    fun partialFromOlderAppVersionIsDiscarded() {
        seedPartial(zipBytes.copyOfRange(0, 50_000), formatVersion = 0)

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertNull(downloadRequests().first().getHeader("Range"))
    }

    @Test
    fun serverZipChangedDuringResumeRestartsFromZero() {
        seedPartial(zipBytes.copyOfRange(0, 50_000))
        val oldEtag = etag
        etag = "\"etag-v2\""
        // The first download-info answer is stale (old ETag) while download.zip
        // already serves a rebuilt ZIP: If-Range fails and a full 200 comes back.
        val infoCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.path ?: return MockResponse().setResponseCode(404)
                if (path.startsWith("/api/books/$bookId/download-info")) {
                    return if (infoCalls.incrementAndGet() == 1) downloadInfo(oldEtag) else downloadInfo()
                }
                return download(request)
            }
        }

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        val downloads = downloadRequests()
        assertEquals(oldEtag, downloads.first().getHeader("If-Range"))
        assertNull("restart must not reuse stale bytes", downloads.last().getHeader("Range"))
    }

    @Test
    fun corruptPartialIsDetectedAndDiscarded() {
        val garbage = zipBytes.copyOfRange(0, 200_000)
        for (i in 1_000 until 150_000) garbage[i] = (garbage[i].toInt() xor 0x5A).toByte()
        seedPartial(garbage)

        val result = runWorker()

        assertTrue("expected retry, got $result", result is ListenableWorker.Result.Retry)
        assertFalse("corrupt partial discarded", store.hasPartial(bookId))
        assertFalse(runBlocking { starRepository.isDownloaded(bookId) })
    }

    @Test
    fun expiredSessionFailsWithoutRetry() {
        downloadInfoStatus = 401

        val result = runWorker()

        assertTrue("expected failure, got $result", result is ListenableWorker.Result.Failure)
        assertEquals(
            "Session expired. Please log in again.",
            (result as ListenableWorker.Result.Failure).outputData.getString(DownloadWorker.KEY_STATUS_MESSAGE)
        )
    }

    @Test
    fun reextractionSkipsCompleteFilesAndRewritesPartialOnes() {
        val bookDir = File(outputRoot, "Test Book").apply { mkdirs() }
        File(bookDir, "ch01.mp3").writeBytes(files.getValue("ch01.mp3"))
        File(bookDir, "ch02.mp3").writeBytes(files.getValue("ch02.mp3").copyOfRange(0, 1000))
        seedPartial(zipBytes)

        val result = runWorker()

        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertExtracted()
        assertEquals(listOf("ch01.mp3", "ch02.mp3", "cover.jpg"), bookDir.list()!!.sorted())
    }

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""
}
