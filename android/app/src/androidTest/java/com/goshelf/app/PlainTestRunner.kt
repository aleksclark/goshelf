package com.goshelf.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom test runner that uses a plain Application instead of GoShelfApp.
 * This avoids Hilt initialization during tests since tests construct
 * all dependencies manually.
 */
class PlainTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application {
        return super.newApplication(cl, Application::class.java.name, context)
    }
}
