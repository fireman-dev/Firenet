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

import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.net.ApiClient

class PushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // اگر پیام از نوع Notification باشد و اپ در فورگراند است، خودمان نوتیف می‌سازیم
        val notif = message.notification ?: return
        showLocalNotification(
            title = notif.title ?: getString(R.string.app_name),
            body = notif.body ?: "",
            data = message.data
        )
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

    private fun showLocalNotification(title: String, body: String, data: Map<String, String>) {
        val channelId = "push_default" // باید با کانال ساخته‌شده یکی باشد

        // مقصد کلیک: MainActivity (در صورت نیاز بعداً به دیپ‌لینک تغییر می‌دهم)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // اگر خواستید data را هم پاس بدهیم:
            // data.forEach { (k, v) -> putExtra(k, v) }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 1001, intent, flags)

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
