package com.v2ray.ang.ui.main

import android.content.Context
import com.v2ray.ang.data.auth.TokenStore
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

object StatusFormatter {

    fun bytesToHuman(bytes: Long?): String {
        if (bytes == null) return "نامحدود"
        if (bytes < 0) return "۰ B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.2f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    data class TrafficOut(val total: String, val remain: String, val used: String)
    fun traffic(totalBytes: Long?, usedBytes: Long?): TrafficOut {
        if (totalBytes == null) {
            // نامحدود: فقط مصرف‌شده نمایش بده
            return TrafficOut("نامحدود", "نامحدود", bytesToHuman(usedBytes ?: 0))
        }
        val used = usedBytes ?: 0
        val remain = max(totalBytes - used, 0)
        return TrafficOut(bytesToHuman(totalBytes), bytesToHuman(remain), bytesToHuman(used))
    }

    data class DaysOut(val remainDays: String)

    fun days(ctx: Context, expireSeconds: Long?): DaysOut {
        if (expireSeconds == null) return DaysOut("نامحدود")
        val nowMs = System.currentTimeMillis()
        val expMs = expireSeconds * 1000
        val remain = max(0.0, ceil((expMs - nowMs) / (24 * 60 * 60 * 1000.0))).toLong()
        return DaysOut("$remain")
    }
}
