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

        // برای جلوگیری از چندبار کال‌بک
        val completed = AtomicBoolean(false)

        ioScope.launch {
            // تلاش اصلی: همیشه پاسخ سرور اولویت دارد
            ApiClient.getStatus(token) { result ->
                if (completed.getAndSet(true)) return@getStatus

                if (result.isSuccess) {
                    // پاسخ معتبر → کش را آپدیت و برگردان
                    val res = result.getOrNull()
                    if (res != null) {
                        MmkvManager.saveLastStatus(res)
                        mainScope.launch { cb(Result.success(res)) }
                    } else {
                        mainScope.launch { cb(Result.failure(Exception("Empty response"))) }
                    }
                    return@getStatus
                }

                // شکست: بررسی نوع خطا
                val msg = result.exceptionOrNull()?.message ?: ""

                // === شرط جدید: سرویس معلق شده است ===
                // طبق درخواست: از کش لود نشود، لاگ‌اوت نشود، اما ارور برگردانده شود (تا تست شود)
                if (msg.contains("سرویس شما توسط ارائه‌دهنده معلق شده است.")) {
                    mainScope.launch { cb(Result.failure(Exception(msg))) }
                    return@getStatus
                }

                // اگر توکن نامعتبر/منقضی است یا 401 → به هیچ عنوان از کش استفاده نکن و فورس لاگ‌اوت کن
                if (
                    msg.contains("401", ignoreCase = true) ||
                    msg.contains("invalid or expired", ignoreCase = true) ||
                    msg.contains("Token is invalid or expired", ignoreCase = true)
                ) {
                    try {
                        // پاک کردن کش MMKV
                        val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
                        mmkv.clearAll()
                        
                        // پاک کردن توکن ذخیره شده
                        TokenStore.clear(ctx)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    mainScope.launch { cb(Result.failure(Exception("HTTP_401"))) }
                    return@getStatus
                }

                // سایر خطاهای شبکه/زمان‌بر → تلاش از کش
                val cached = MmkvManager.loadLastStatus()
                if (cached != null) {
                    mainScope.launch { cb(Result.success(cached)) }
                } else {
                    mainScope.launch { cb(Result.failure(result.exceptionOrNull() ?: Exception("Network error"))) }
                }
            }

            // اختیاراً: سقف انتظار برای تضمین اینکه کال‌بک آویزان نماند
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