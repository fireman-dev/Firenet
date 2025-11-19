package com.v2ray.ang.data.auth

import android.content.Context
import android.provider.Settings

object TokenStore {
    private const val PREF = "auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USERNAME = "auth_username"
    private const val KEY_FIRST_LOGIN_TS = "first_login_ts"

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

    fun deviceId(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        val model = android.os.Build.MODEL.replace("\\s+".toRegex(), "")
        return "android_${androidId}_$model"
    }
}
