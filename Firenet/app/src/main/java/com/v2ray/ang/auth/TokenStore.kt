package com.v2ray.ang.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.os.Build
import java.util.UUID

object TokenStore {
    private const val PREF = "auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USERNAME = "auth_username"
    private const val KEY_FIRST_LOGIN_TS = "first_login_ts"
    private const val KEY_LEGACY_UUID = "device_legacy_uuid" // کلید جدید برای ذخیره UUID در شرایط اضطراری

    fun save(ctx: Context, token: String, username: String) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val editor = sp.edit()
        editor.putString(KEY_TOKEN, token)
        editor.putString(KEY_USERNAME, username)
        if (!sp.contains(KEY_FIRST_LOGIN_TS)) {
            editor.putLong(KEY_FIRST_LOGIN_TS, System.currentTimeMillis()) // اولین ورود
        }
        editor.apply()
    }
    
    fun firstLoginTs(ctx: Context): Long? {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_FIRST_LOGIN_TS, -1L)
        return if (v > 0) v else null
    }
    
    fun token(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * تولید شناسه دستگاه هوشمند:
     * 1. اولویت اول: ANDROID_ID (ثابت و پایدار با حذف نصب)
     * 2. اولویت دوم (در صورت شکست اولی): تولید UUID تصادفی و ذخیره آن (روش قدیمی)
     */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        try {
            // تلاش برای گرفتن شناسه ثابت اندروید
            val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            
            // بررسی اعتبار ANDROID_ID (نباید خالی یا نال باشد و نباید شناسه معروف باگ‌دار باشد)
            // 9774d56d682e549c یک شناسه معروف باگ‌دار در برخی گوشی‌های قدیمی اندروید است
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                val model = Build.MODEL.replace("\\s+".toRegex(), "")
                return "android_${androidId}_$model"
            }
        } catch (e: Exception) {
            // نادیده گرفتن خطا و رفتن به سراغ روش جایگزین
        }

        // --- روش جایگزین (Fallback) ---
        // اگر به هر دلیلی (باگ سیستم، رام کاستوم، قدیمی بودن) شناسه بالا گرفته نشد،
        // یک کد تصادفی تولید می‌کنیم و نگه می‌داریم.
        return getOrGenerateLegacyUuid(ctx)
    }

    private fun getOrGenerateLegacyUuid(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        
        // 1. آیا قبلاً تولید کردیم؟
        var uuid = sp.getString(KEY_LEGACY_UUID, null)
        
        if (uuid.isNullOrEmpty()) {
            // 2. اگر نه، یکی بساز
            uuid = UUID.randomUUID().toString().replace("-", "")
            // 3. ذخیره کن تا دفعه بعد تغییر نکنه
            sp.edit().putString(KEY_LEGACY_UUID, uuid).apply()
        }
        
        val model = Build.MODEL.replace("\\s+".toRegex(), "")
        return "legacy_${uuid}_$model"
    }
}