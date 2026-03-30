package com.altnautica.gcs

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ADOSApplication : Application() {

    @Inject lateinit var lifecycleManager: AppLifecycleManager

    override fun onCreate() {
        super.onCreate()
        lifecycleManager.initialize()
    }
}
