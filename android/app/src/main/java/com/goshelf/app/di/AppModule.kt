package com.goshelf.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goshelf.app.data.api.GoShelfApi
import com.goshelf.app.data.repository.AuthRepository
import com.goshelf.app.data.repository.BookRepository
import com.goshelf.app.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "goshelf_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        sharedPreferences: SharedPreferences
    ): SettingsRepository {
        return SettingsRepository(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(settingsRepository: SettingsRepository): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideGoShelfApi(
        okHttpClient: OkHttpClient,
        settingsRepository: SettingsRepository
    ): GoShelfApi {
        return GoShelfApi(okHttpClient, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        goShelfApi: GoShelfApi,
        settingsRepository: SettingsRepository
    ): AuthRepository {
        return AuthRepository(goShelfApi, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideBookRepository(
        goShelfApi: GoShelfApi,
        settingsRepository: SettingsRepository
    ): BookRepository {
        return BookRepository(goShelfApi, settingsRepository)
    }
}
