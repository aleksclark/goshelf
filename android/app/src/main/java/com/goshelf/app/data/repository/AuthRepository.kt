package com.goshelf.app.data.repository

import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.api.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val api: GoShelfApi,
    private val settingsRepository: SettingsRepository
) {
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val result = api.login(username, password)
        if (result.success && result.sessionToken != null) {
            settingsRepository.setSessionToken(result.sessionToken)
            settingsRepository.setUsername(username)
        }
        result
    }

    fun logout() {
        settingsRepository.logout()
    }

    fun isLoggedIn(): Boolean {
        return settingsRepository.isLoggedIn()
    }
}
