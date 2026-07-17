package com.goshelf.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.repository.AuthRepository
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class GoShelfIntegrationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var api: GoShelfApi
    private lateinit var authRepository: AuthRepository
    private lateinit var bookRepository: BookRepository
    private lateinit var context: Context

    private val booksJson = """
        [
            {
                "id": 1,
                "title": "The Hitchhiker's Guide to the Galaxy",
                "author": "Douglas Adams",
                "authorId": 10,
                "series": "Hitchhiker's Guide #1",
                "fileCount": 3,
                "totalSize": 104857600,
                "hasCover": true
            },
            {
                "id": 2,
                "title": "Dune",
                "author": "Frank Herbert",
                "authorId": 20,
                "series": null,
                "fileCount": 5,
                "totalSize": 209715200,
                "hasCover": true
            }
        ]
    """.trimIndent()

    private val bookDetailJson = """
        {
            "id": 1,
            "title": "The Hitchhiker's Guide to the Galaxy",
            "author": "Douglas Adams",
            "authorId": 10,
            "series": "Hitchhiker's Guide #1",
            "overview": "The story of Arthur Dent and his adventures through space.",
            "hasCover": true,
            "files": [
                {"name": "chapter01.mp3", "size": 34952533},
                {"name": "chapter02.mp3", "size": 34952533},
                {"name": "chapter03.mp3", "size": 34952534}
            ],
            "totalSize": 104857600
        }
    """.trimIndent()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.method == "POST" && request.path == "/login" -> {
                        val body = request.body.readUtf8()
                        if (body.contains("username=testuser") && body.contains("password=testpass")) {
                            MockResponse()
                                .setResponseCode(303)
                                .addHeader("Set-Cookie", "session=test-session-token; Path=/; HttpOnly")
                                .addHeader("Location", "/")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody("<html>Invalid credentials</html>")
                        }
                    }
                    request.path == "/api/books" -> {
                        val cookie = request.getHeader("Cookie")
                        if (cookie != null && cookie.contains("session=test-session-token")) {
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("Content-Type", "application/json")
                                .setBody(booksJson)
                        } else {
                            MockResponse()
                                .setResponseCode(303)
                                .addHeader("Location", "/login")
                        }
                    }
                    request.path?.startsWith("/api/books/") == true -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(bookDetailJson)
                    }
                    request.path?.startsWith("/books/") == true && request.path?.endsWith("/download.zip") == true -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/zip")
                            .setBody(okio.Buffer().write(createTestZip()))
                    }
                    request.path?.startsWith("/covers/") == true -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "image/jpeg")
                            .setBody(okio.Buffer().write(createTinyJpeg()))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()

        val prefs: SharedPreferences = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        settingsRepository = SettingsRepository(prefs)
        settingsRepository.setServerUrl(mockServer.url("/").toString().trimEnd('/'))

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val session = settingsRepository.getSessionToken()
                if (session != null) {
                    val request = original.newBuilder()
                        .addHeader("Cookie", "session=$session")
                        .build()
                    chain.proceed(request)
                } else {
                    chain.proceed(original)
                }
            }
            .followRedirects(false)
            .build()

        api = GoShelfApi(client, settingsRepository)
        authRepository = AuthRepository(api, settingsRepository)
        bookRepository = BookRepository(api, settingsRepository)
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun testLoginSuccess() = runBlocking {
        val result = authRepository.login("testuser", "testpass")
        assertTrue("Login should succeed", result.success)
        assertEquals("test-session-token", result.sessionToken)
        assertTrue("Should be logged in", authRepository.isLoggedIn())
    }

    @Test
    fun testLoginFailure() = runBlocking {
        val result = authRepository.login("baduser", "badpass")
        assertFalse("Login should fail", result.success)
        assertFalse("Should not be logged in", authRepository.isLoggedIn())
    }

    @Test
    fun testFetchBooksAuthenticated() = runBlocking {
        val loginResult = authRepository.login("testuser", "testpass")
        assertTrue(loginResult.success)

        val books = bookRepository.getBooks()
        assertEquals(2, books.size)
        assertEquals("The Hitchhiker's Guide to the Galaxy", books[0].title)
        assertEquals("Douglas Adams", books[0].author)
        assertEquals("Dune", books[1].title)
        assertEquals("Frank Herbert", books[1].author)
    }

    @Test
    fun testFetchBooksUnauthenticated() = runBlocking {
        try {
            bookRepository.getBooks()
            fail("Should throw IOException for unauthenticated request")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Not authenticated") == true)
        }
    }

    @Test
    fun testFetchBookDetail() = runBlocking {
        authRepository.login("testuser", "testpass")

        val detail = bookRepository.getBookDetail(1)
        assertEquals(1, detail.id)
        assertEquals("The Hitchhiker's Guide to the Galaxy", detail.title)
        assertEquals("Douglas Adams", detail.author)
        assertEquals(3, detail.files.size)
        assertEquals("chapter01.mp3", detail.files[0].name)
        assertNotNull(detail.overview)
    }

    @Test
    fun testDownloadAndExtract() = runBlocking {
        authRepository.login("testuser", "testpass")

        val (inputStream, contentLength) = api.downloadZip(1)
        assertTrue("Content length should be positive", contentLength > 0)

        val outputDir = File(context.cacheDir, "test_extract")
        outputDir.mkdirs()

        val zipData = inputStream.readBytes()
        assertTrue("ZIP data should not be empty", zipData.isNotEmpty())

        val zipInputStream = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipData))
        var entry = zipInputStream.nextEntry
        val extractedFiles = mutableListOf<String>()
        while (entry != null) {
            if (!entry.isDirectory) {
                val outputFile = File(outputDir, entry.name)
                outputFile.outputStream().use { fos ->
                    zipInputStream.copyTo(fos)
                }
                extractedFiles.add(entry.name)
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        zipInputStream.close()

        assertEquals("Should extract 2 audio files", 2, extractedFiles.size)
        assertTrue("Should contain chapter01.mp3", extractedFiles.contains("chapter01.mp3"))
        assertTrue("Should contain chapter02.mp3", extractedFiles.contains("chapter02.mp3"))

        val file1 = File(outputDir, "chapter01.mp3")
        assertTrue("Extracted file should exist", file1.exists())
        assertTrue("Extracted file should have content", file1.length() > 0)

        outputDir.deleteRecursively()
    }

    @Test
    fun testLogout() = runBlocking {
        authRepository.login("testuser", "testpass")
        assertTrue(authRepository.isLoggedIn())

        authRepository.logout()
        assertFalse(authRepository.isLoggedIn())
    }

    @Test
    fun testSettingsRepository() {
        settingsRepository.setDownloadDirectory("/sdcard/Music/GoShelf")
        assertEquals("/sdcard/Music/GoShelf", settingsRepository.getDownloadDirectory())

        settingsRepository.setServerUrl("https://example.com")
        assertEquals("https://example.com", settingsRepository.getServerUrl())
    }

    private fun createTestZip(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entry1 = ZipEntry("chapter01.mp3")
            zos.putNextEntry(entry1)
            zos.write(ByteArray(1024) { 0xFF.toByte() })
            zos.closeEntry()

            val entry2 = ZipEntry("chapter02.mp3")
            zos.putNextEntry(entry2)
            zos.write(ByteArray(1024) { 0xFE.toByte() })
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun createTinyJpeg(): ByteArray {
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )
    }
}
