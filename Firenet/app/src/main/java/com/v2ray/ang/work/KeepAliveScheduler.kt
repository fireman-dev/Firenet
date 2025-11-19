package com.v2ray.ang.work

import android.content.Context
import androidx.work.*

object KeepAliveScheduler {
    private const val UNIQUE = "keepalive_worker"

    fun start(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequest.Builder(KeepAliveWorker::class.java, 15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
    }
}
