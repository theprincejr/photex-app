package com.example.photx

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Root Android [Application] entry point for PhotX, initialized by Hilt and configuring
 * WorkManager to construct `@HiltWorker` instances via [HiltWorkerFactory].
 */
@HiltAndroidApp
class PhotxApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
