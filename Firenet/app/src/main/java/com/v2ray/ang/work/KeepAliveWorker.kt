package com.v2ray.ang.work

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.net.ApiClient

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val token = TokenStore.token(applicationContext) ?: return Result.success()
        if (!V2RayServiceManager.isRunning()) return Result.success()

        var done: Result = Result.success()
        val lock = Object()

        ApiClient.postKeepAlive(token) { r ->
            synchronized(lock) {
                if (r.isSuccess) {
                    done = Result.success()
                } else {
                    val msg = r.exceptionOrNull()?.message ?: ""
                    if (msg == "401") {
                        // قطع VPN + سیگنال خروج اجباری + پاکسازی توکن
                        V2RayServiceManager.stopVService(applicationContext)
                        TokenStore.clear(applicationContext)
                        KeepAliveScheduler.stop(applicationContext)
                        applicationContext.sendBroadcast(Intent(AppConfig.ACTION_FORCE_LOGOUT))
                        done = Result.success()
                    } else {
                        done = Result.retry()
                    }
                }
                lock.notify()
            }
        }

        synchronized(lock) { lock.wait(25_000) } // حداکثر 25 ثانیه برای کال‌بک
        return done
    }
}
