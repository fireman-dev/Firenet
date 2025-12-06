package com.v2ray.ang.data.auth

import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.net.ApiClient
import com.v2ray.ang.net.StatusResponse
import com.v2ray.ang.handler.MmkvManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class AuthRepository(private val ctx: Context) {

    private fun appVersion(): String {
        return try {
            val pm = ctx.packageManager
            val pInfo = pm.getPackageInfo(ctx.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    fun login(username: String, password: String, cb: (Result<String>) -> Unit) {
        val version = appVersion()
        ApiClient.postLogin(username, password, TokenStore.deviceId(ctx), version, cb)
    }

    fun status(token: String, cb: (Result<StatusResponse>) -> Unit) {
        val ioScope = CoroutineScope(Dispatchers.IO)
        val mainScope = CoroutineScope(Dispatchers.Main)

        // برای جلوگیری از چندبار کال‌بک (تداخل تایم‌اوت و پاسخ سرور)
        val completed = AtomicBoolean(false)

        ioScope.launch {
            ApiClient.getStatus(token) { result ->
                if (completed.getAndSet(true)) return@getStatus

                // -----------------------------------------------------------
                // 1. حالت موفقیت: سرور پاسخ داده است (200 OK)
                // -----------------------------------------------------------
                if (result.isSuccess) {
                    val res = result.getOrNull()
                    if (res != null) {
                        // ذخیره در کش برای استفاده‌های بعدی (فقط وقتی توکن معتبر است)
                        MmkvManager.saveLastStatus(res)
                        mainScope.launch { cb(Result.success(res)) }
                    } else {
                        mainScope.launch { cb(Result.failure(Exception("Empty response"))) }
                    }
                    return@getStatus
                }

                // -----------------------------------------------------------
                // 2. تحلیل خطا: چرا درخواست ناموفق بود؟
                // -----------------------------------------------------------
                val exception = result.exceptionOrNull()
                val msg = exception?.message ?: ""

                // الف) تشخیص ساسپند بودن (مهم: قبل از لود کش بررسی شود)
                if (msg.contains("HTTP_403", true) || 
                    msg.contains("suspended", true) || 
                    msg.contains("سرویس شما مسدود", true)) {
                    
                    // بازگشت خطای مشخص برای ساسپند - بدون لود کش
                    mainScope.launch { cb(Result.failure(Exception("HTTP_403"))) }
                    return@getStatus
                }

                // ب) تشخیص عدم اعتبار توکن
                if (msg.contains("HTTP_401", true) ||
                    msg.contains("Token is invalid", true) ||
                    msg.contains("invalid or expired", true) ||
                    msg.contains("Unauthenticated", true)) {
                    
                    try {
                        val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
                        mmkv.clearAll()
                        TokenStore.clear(ctx)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    // بازگشت خطا با تگ مشخص برای هدایت به صفحه لاگین
                    mainScope.launch { cb(Result.failure(Exception("HTTP_401"))) }
                    return@getStatus
                }

                // -----------------------------------------------------------
                // 3. حالت خطای شبکه / سرور (Timeout, 500, DNS, ...)
                // -----------------------------------------------------------
                // فقط در صورتی به اینجا می‌رسیم که خطا 401 یا تعلیق نبوده باشد.
                val cached = MmkvManager.loadLastStatus()
                if (cached != null) {
                    mainScope.launch { cb(Result.success(cached)) }
                } else {
                    mainScope.launch { cb(Result.failure(exception ?: Exception("Network error"))) }
                }
            }

            // تایم‌اوت کلی برای جلوگیری از هنگ کردن
            withTimeoutOrNull(60_000L) {
                while (!completed.get()) delay(100)
            }
        }
    }

    fun updatePromptSeen(token: String, cb: (Result<Unit>) -> Unit) {
        ApiClient.postUpdatePromptSeen(token, cb)
    }

    fun logout(token: String, cb: (Result<Unit>) -> Unit) {
        val ioScope = CoroutineScope(Dispatchers.IO)
        val mainScope = CoroutineScope(Dispatchers.Main)

        ioScope.launch {
            try {
                // پاک کردن داده‌های ذخیره‌شده
                val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
                mmkv.clearAll()
                TokenStore.clear(ctx)

                // تلاش برای logout سرور (در صورت در دسترس بودن اینترنت)
                ApiClient.postLogout(token) { _ ->
                    mainScope.launch { cb(Result.success(Unit)) }
                }
            } catch (e: Exception) {
                mainScope.launch { cb(Result.success(Unit)) }
            }
        }
    }

    fun reportAppUpdateIfNeeded(token: String, cb: (Result<Boolean>) -> Unit) {
        try {
            // نسخه فعلی اپ
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val current = pInfo.versionName ?: "0.0.0"

            // خواندن آخرین نسخهٔ گزارش‌شده از SharedPreferences
            val sp = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val lastReported = sp.getString("last_reported_app_version", null)

            // اگر تغییری نیست، نیازی به تماس با سرور نیست
            if (lastReported != null && lastReported == current) {
                cb(Result.success(false))
                return
            }

            // گزارش نسخهٔ جدید به پنل
            ApiClient.postReportUpdate(token, current) { r ->
                if (r.isSuccess) {
                    // ذخیرهٔ نسخهٔ گزارش‌شده
                    sp.edit().putString("last_reported_app_version", current).apply()
                    cb(Result.success(true))
                } else {
                    cb(Result.failure(r.exceptionOrNull() ?: Exception("نامشخص")))
                }
            }
        } catch (e: Exception) {
            cb(Result.failure(e))
        }
    }
}