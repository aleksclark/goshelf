package com.goshelf.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.goshelf.app.data.repository.SettingsRepository
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class BookListItem(
    val id: Int = 0,
    val title: String = "",
    val author: String = "",
    val authorId: Int = 0,
    val series: String? = null,
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val hasCover: Boolean = false
)

data class BookFileInfo(
    val name: String = "",
    val size: Long = 0
)

data class BookDetail(
    val id: Int = 0,
    val title: String = "",
    val author: String = "",
    val authorId: Int = 0,
    val series: String? = null,
    val overview: String? = null,
    val hasCover: Boolean = false,
    val files: List<BookFileInfo> = emptyList(),
    val totalSize: Long = 0
)

data class LoginResult(
    val success: Boolean,
    val sessionToken: String? = null,
    val error: String? = null
)

data class DownloadInfo(
    val status: String? = null,
    val totalSize: Long = 0,
    val etag: String = "",
    val filename: String = "",
    val fileCount: Int = 0,
    val skippedFiles: Int = 0,
    val retryAfter: Int = 0
)

sealed interface DownloadInfoResult {
    data class Ready(val info: DownloadInfo) : DownloadInfoResult
    data class Preparing(val retryAfterSeconds: Int) : DownloadInfoResult
}

/** Session is missing or expired; retrying will not help until the user logs in again. */
class NotAuthenticatedException : IOException("Not authenticated")

/** Server answered with an HTTP error status. */
class HttpStatusException(val code: Int, message: String) : IOException(message) {
    /** 5xx, 408 and 429 are worth retrying; other 4xx are not. */
    val isRetryable: Boolean get() = code >= 500 || code == 408 || code == 429
}

/**
 * An open download.zip response. Callers must close it; [cancel] aborts a
 * blocked read from another thread.
 */
class ZipStream(
    private val call: Call,
    private val response: Response,
    val code: Int,
    val contentRange: String?,
    val etag: String?,
    val contentLength: Long,
    val body: InputStream
) : Closeable {
    fun cancel() = call.cancel()
    override fun close() = response.close()
}

data class APIAuthor(
    val id: Int = 0,
    val name: String = "",
    val bookCount: Int = 0,
    val hasCover: Boolean = false,
    val firstBookId: Int = 0
)

data class APISeries(
    val name: String = "",
    val slug: String = "",
    val bookCount: Int = 0,
    val hasCover: Boolean = false,
    val firstBookId: Int = 0
)

data class AuthorDetailResponse(
    val author: APIAuthor = APIAuthor(),
    val series: List<APISeries> = emptyList(),
    val books: List<BookListItem> = emptyList()
)

data class SeriesDetailResponse(
    val name: String = "",
    val books: List<BookListItem> = emptyList()
)

class GoShelfApi(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "GoShelfApi"
        private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 45L
        private const val DOWNLOAD_INFO_READ_TIMEOUT_SECONDS = 90L
    }

    /**
     * Client for ZIP transfers. The server builds the ZIP before download-info
     * answers ready, so bytes should flow immediately; a short read timeout
     * detects dead connections (screen-off Wi-Fi drops, network handoffs)
     * quickly instead of hanging for minutes before the resume kicks in.
     */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** download-info may block on the server for up to ~20s while the ZIP builds. */
    private val downloadInfoClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(DOWNLOAD_INFO_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun baseUrl(): String = settingsRepository.getServerUrl()

    fun login(username: String, password: String): LoginResult {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()

        val request = Request.Builder()
            .url("${baseUrl()}/login")
            .post(body)
            .build()

        // Use a client without the cookie interceptor for login
        val loginClient = client.newBuilder()
            .followRedirects(false)
            .build()

        val response = loginClient.newCall(request).execute()

        return when (response.code) {
            303, 302 -> {
                // Successful login - extract session cookie
                val cookies = response.headers("Set-Cookie")
                val sessionCookie = cookies.firstOrNull { it.startsWith("session=") }
                if (sessionCookie != null) {
                    val token = sessionCookie
                        .substringAfter("session=")
                        .substringBefore(";")
                    LoginResult(success = true, sessionToken = token)
                } else {
                    LoginResult(success = false, error = "No session cookie received")
                }
            }
            200 -> {
                // Login page re-rendered with error (form validation failed)
                LoginResult(success = false, error = "Invalid username or password")
            }
            else -> {
                LoginResult(success = false, error = "Server error: ${response.code}")
            }
        }
    }

    fun getBooks(): List<BookListItem> {
        val request = Request.Builder()
            .url("${baseUrl()}/api/books")
            .get()
            .build()

        val response = client.newCall(request).execute()

        if (response.code == 303 || response.code == 302) {
            throw IOException("Not authenticated")
        }

        if (!response.isSuccessful) {
            throw IOException("Failed to fetch books: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")

        // Guard against server returning error JSON instead of array
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("[")) {
            Log.w(TAG, "getBooks: unexpected response format: ${body.take(100)}")
            throw IOException("Server error: unexpected response")
        }

        return try {
            val type = object : TypeToken<List<BookListItem>>() {}.type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "getBooks: JSON parse error", e)
            throw IOException("Failed to parse library data: ${e.message}")
        }
    }

    fun getBookDetail(bookId: Int): BookDetail {
        val request = Request.Builder()
            .url("${baseUrl()}/api/books/$bookId")
            .get()
            .build()

        val response = client.newCall(request).execute()

        if (response.code == 303 || response.code == 302) {
            throw IOException("Not authenticated")
        }

        if (!response.isSuccessful) {
            throw IOException("Failed to fetch book detail: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")

        return try {
            gson.fromJson(body, BookDetail::class.java)
                ?: throw IOException("Failed to parse book detail")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "getBookDetail: JSON parse error", e)
            throw IOException("Failed to parse book data: ${e.message}")
        }
    }

    fun getCoverUrl(bookId: Int): String {
        return "${baseUrl()}/covers/$bookId"
    }

    /**
     * Asks the server to prepare the book ZIP and returns its exact size and
     * ETag once ready. Returns [DownloadInfoResult.Preparing] while the server
     * is still building it; callers should poll after the suggested delay.
     */
    fun getDownloadInfo(bookId: Int): DownloadInfoResult {
        val request = Request.Builder()
            .url("${baseUrl()}/api/books/$bookId/download-info?async=1")
            .get()
            .build()

        downloadInfoClient.newCall(request).execute().use { response ->
            when (response.code) {
                401, 302, 303 -> throw NotAuthenticatedException()
                202 -> {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 5
                    return DownloadInfoResult.Preparing(retryAfter)
                }
            }
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "Failed to get download info: ${response.code}")
            }

            val body = response.body?.string() ?: throw IOException("Empty response")
            val info = try {
                gson.fromJson(body, DownloadInfo::class.java)
                    ?: throw IOException("Failed to parse download info")
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "getDownloadInfo: JSON parse error", e)
                throw IOException("Failed to parse download info: ${e.message}")
            }
            if (info.status == "preparing") {
                return DownloadInfoResult.Preparing(info.retryAfter.takeIf { it > 0 } ?: 5)
            }
            if (info.etag.isEmpty() || info.totalSize <= 0) {
                throw IOException("Server returned incomplete download info")
            }
            return DownloadInfoResult.Ready(info)
        }
    }

    /**
     * Opens the book ZIP starting at [rangeStart]. When resuming, [ifRange]
     * must be the ETag the partial data belongs to; the server then answers
     * 206 for the same ZIP or a full 200 if it changed.
     */
    fun openZipStream(bookId: Int, rangeStart: Long, ifRange: String?): ZipStream {
        val requestBuilder = Request.Builder()
            .url("${baseUrl()}/books/$bookId/download.zip")
            .get()

        if (rangeStart > 0) {
            requestBuilder.header("Range", "bytes=$rangeStart-")
            if (!ifRange.isNullOrEmpty()) {
                requestBuilder.header("If-Range", ifRange)
            }
        }

        val call = downloadClient.newCall(requestBuilder.build())
        val response = call.execute()

        if (response.code == 401 || response.code == 303 || response.code == 302) {
            response.close()
            throw NotAuthenticatedException()
        }
        if (!response.isSuccessful && response.code != 416) {
            response.close()
            throw HttpStatusException(response.code, "Failed to download: ${response.code}")
        }

        val body = response.body ?: run {
            response.close()
            throw IOException("Empty response body")
        }
        return ZipStream(
            call = call,
            response = response,
            code = response.code,
            contentRange = response.header("Content-Range"),
            etag = response.header("ETag"),
            contentLength = body.contentLength(),
            body = body.byteStream()
        )
    }

    /**
     * Downloads the whole ZIP in a single request (no resume).
     */
    fun downloadZip(bookId: Int): Pair<InputStream, Long> {
        val stream = openZipStream(bookId, 0, null)
        return Pair(stream.body, if (stream.contentLength >= 0) stream.contentLength else 0L)
    }

    fun getAuthors(): List<APIAuthor> {
        val request = Request.Builder()
            .url("${baseUrl()}/api/authors")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (response.code == 303 || response.code == 302) throw IOException("Not authenticated")
        if (!response.isSuccessful) throw IOException("Failed to fetch authors: ${response.code}")
        val body = response.body?.string() ?: throw IOException("Empty response")
        return try {
            val type = object : TypeToken<List<APIAuthor>>() {}.type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            throw IOException("Failed to parse authors: ${e.message}")
        }
    }

    fun getAuthorDetail(authorId: Int): AuthorDetailResponse {
        val request = Request.Builder()
            .url("${baseUrl()}/api/authors/$authorId")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (response.code == 303 || response.code == 302) throw IOException("Not authenticated")
        if (!response.isSuccessful) throw IOException("Failed to fetch author: ${response.code}")
        val body = response.body?.string() ?: throw IOException("Empty response")
        return try {
            gson.fromJson(body, AuthorDetailResponse::class.java)
                ?: throw IOException("Failed to parse author detail")
        } catch (e: JsonSyntaxException) {
            throw IOException("Failed to parse author data: ${e.message}")
        }
    }

    fun getSeries(): List<APISeries> {
        val request = Request.Builder()
            .url("${baseUrl()}/api/series")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (response.code == 303 || response.code == 302) throw IOException("Not authenticated")
        if (!response.isSuccessful) throw IOException("Failed to fetch series: ${response.code}")
        val body = response.body?.string() ?: throw IOException("Empty response")
        return try {
            val type = object : TypeToken<List<APISeries>>() {}.type
            gson.fromJson(body, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            throw IOException("Failed to parse series: ${e.message}")
        }
    }

    fun getSeriesDetail(slug: String): SeriesDetailResponse {
        val request = Request.Builder()
            .url("${baseUrl()}/api/series/$slug")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (response.code == 303 || response.code == 302) throw IOException("Not authenticated")
        if (!response.isSuccessful) throw IOException("Failed to fetch series: ${response.code}")
        val body = response.body?.string() ?: throw IOException("Empty response")
        return try {
            gson.fromJson(body, SeriesDetailResponse::class.java)
                ?: throw IOException("Failed to parse series detail")
        } catch (e: JsonSyntaxException) {
            throw IOException("Failed to parse series data: ${e.message}")
        }
    }
}
