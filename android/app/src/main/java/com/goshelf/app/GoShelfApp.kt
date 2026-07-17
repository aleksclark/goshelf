package com.goshelf.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GoShelfApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() {
            return if (::workerFactory.isInitialized) {
                Configuration.Builder()
                    .setWorkerFactory(workerFactory)
                    .setMinimumLoggingLevel(Log.INFO)
                    .build()
            } else {
                Log.w("GoShelfApp", "WorkerFactory not yet initialized, using default config")
                Configuration.Builder()
                    .setMinimumLoggingLevel(Log.INFO)
                    .build()
            }
        }
}
