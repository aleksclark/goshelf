package com.goshelf.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.goshelf.app.data.worker.DownloadNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class GoShelfApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "GoShelfApp"
    }

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
                Log.w(TAG, "WorkerFactory not yet initialized, using default config")
                Configuration.Builder()
                    .setMinimumLoggingLevel(Log.INFO)
                    .build()
            }
        }

    override fun onCreate() {
        super.onCreate()

        // Initialize notification channel early
        DownloadNotificationHelper(this)

        // Scan for orphaned .download files
        scanOrphanedDownloads()
    }

    private fun scanOrphanedDownloads() {
        val downloadsDir = File(cacheDir, "downloads")
        if (!downloadsDir.exists()) return

        val downloadFiles = downloadsDir.listFiles { file ->
            file.name.endsWith(".download")
        } ?: return

        if (downloadFiles.isNotEmpty()) {
            Log.i(TAG, "Found ${downloadFiles.size} orphaned download metadata file(s):")
            downloadFiles.forEach { file ->
                val partFileName = file.name.replace(".download", ".part")
                val partFile = File(downloadsDir, partFileName)
                val partSize = if (partFile.exists()) partFile.length() else 0L
                Log.i(TAG, "  - ${file.name}: partial data = ${formatBytes(partSize)}")
            }
            Log.i(TAG, "These downloads can be resumed from the book detail screen.")
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
