package com.kooo.evcam.v2.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import java.util.concurrent.TimeUnit

object V2KeepAliveScheduler {
    private const val UNIQUE_WORK_NAME = "v2_keep_alive_backup"

    fun schedule(context: Context) {
        if (!V2KeepAliveSettings.isKeepAliveEnabled(context)) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<V2KeepAliveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
