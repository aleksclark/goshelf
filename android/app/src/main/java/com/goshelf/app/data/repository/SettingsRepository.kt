package com.goshelf.app.data.repository

import android.content.SharedPreferences

class SettingsRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_DOWNLOAD_DIR = "download_dir"
        private const val DEFAULT_SERVER_URL = "https://books.clark.team"
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url.trimEnd('/')).apply()
    }

    fun getSessionToken(): String? {
        return prefs.getString(KEY_SESSION_TOKEN, null)
    }

    fun setSessionToken(token: String?) {
        prefs.edit().putString(KEY_SESSION_TOKEN, token).apply()
    }

    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    fun setUsername(username: String?) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    /**
     * Returns the download directory as a SAF content URI string,
     * or empty string if not set.
     */
    fun getDownloadDirectory(): String {
        return prefs.getString(KEY_DOWNLOAD_DIR, "") ?: ""
    }

    fun setDownloadDirectory(uriString: String) {
        prefs.edit().putString(KEY_DOWNLOAD_DIR, uriString).apply()
    }

    fun isDownloadDirConfigured(): Boolean {
        return getDownloadDirectory().isNotEmpty()
    }

    fun isLoggedIn(): Boolean {
        return getSessionToken() != null
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_USERNAME)
            .apply()
    }
}
