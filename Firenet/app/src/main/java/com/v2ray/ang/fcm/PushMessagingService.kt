package com.v2ray.ang.fcm

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.v2ray.ang.R
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.ui.LoginActivity
import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.net.ApiClient
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.AppConfig
import com.tencent.mmkv.MMKV

class PushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // 1. بررسی پیام‌های دیتا (دستورات سیستمی مثل Force Logout)
        if (message.data.isNotEmpty()) {
            val action = message.data["action"]
            if (action == "FORCE_LOGOUT") {
                performForceLogout()
                return // چون دستور سیستمی بود، دیگر ادامه نده
            }
        }

        // 2. اگر پیام از نوع Notification باشد و اپ در فورگراند است (رفتار قبلی)
        val notif = message.notification ?: return
        showLocalNotification(
            title = notif.title ?: getString(R.string.app_name),
            body = notif.body ?: "",
            intentTarget = MainActivity::class.java // نوتیف معمولی میره به Main
        )
    }

    private fun performForceLogout() {
        try {
            Log.d("FCM", "Received FORCE_LOGOUT command. Clearing data...")

            // الف) پاک‌سازی کامل داده‌ها
            val mmkv = MMKV.defaultMMKV()
            mmkv.clearAll()
            TokenStore.clear(applicationContext)

            // ب) قطع اتصال VPN اگر روشن است
            MessageUtil.sendMsg2Service(applicationContext, AppConfig.MSG_STOP_V2RAY, "")

            // ج) نمایش نوتیفیکیشن برای اطلاع کاربر
            showLocalNotification(
                title = getString(R.string.app_name),
                body = "نشست شما توسط مدیر بسته شد. لطفاً مجدداً وارد شوید.",
                intentTarget = LoginActivity::class.java // کلیک روی این نوتیف میره به لاگین
            )

            // د) هدایت آنی به صفحه لاگین (اگر کاربر داخل برنامه باشد)
            val intent = Intent(applicationContext, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Log.e("FCM", "Error executing force logout", e)
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "new token: $token")
        val jwt = TokenStore.token(applicationContext) ?: return  // فقط اگر کاربر لاگین است
        ApiClient.postUpdateFcmToken(jwt, token) { r ->
            if (r.isFailure) {
                // + ADD: یک Retry سبک طبق سیاست تو
                Thread {
                    try {
                        Thread.sleep(1500)
                        ApiClient.postUpdateFcmToken(jwt, token) { /* نتیجه مهم نیست */ }
                    } catch (_: Exception) {}
                }.start()
            }
        }
    }

    // تابع کمکی نمایش نوتیفیکیشن (با قابلیت تعیین مقصد کلیک)
    private fun showLocalNotification(title: String, body: String, intentTarget: Class<*>) {
        val channelId = "push_default" // باید با کانال ساخته‌شده یکی باشد

        val intent = Intent(this, intentTarget).apply {
            // اگر مقصد لاگین است، پشته را خالی کن
            if (intentTarget == LoginActivity::class.java) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, flags)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)      // ← آیکن استتوس‌بار
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        with(NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}