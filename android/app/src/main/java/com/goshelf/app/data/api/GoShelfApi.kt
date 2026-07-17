package com.goshelf.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.goshelf.app.data.repository.SettingsRepository
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

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

class GoShelfApi(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "GoShelfApi"
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

    fun downloadZip(bookId: Int): Pair<InputStream, Long> {
        val request = Request.Builder()
            .url("${baseUrl()}/books/$bookId/download.zip")
            .get()
            .build()

        val response = client.newCall(request).execute()

        if (response.code == 303 || response.code == 302) {
            throw IOException("Not authenticated")
        }

        if (!response.isSuccessful) {
            throw IOException("Failed to download: ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()
        return Pair(body.byteStream(), contentLength)
    }
}
