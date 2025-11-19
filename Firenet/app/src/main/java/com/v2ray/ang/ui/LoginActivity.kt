package com.v2ray.ang.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.R
import com.v2ray.ang.data.auth.AuthRepository
import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.net.StatusResponse
import com.v2ray.ang.ui.MainActivity

class LoginActivity : AppCompatActivity() {
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnSupport: ImageButton
    private val repo by lazy { AuthRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TokenStore.token(this)?.let { goMain(); return }

        setContentView(R.layout.activity_login)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnLogin = findViewById(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) { toast("نام کاربری و رمز عبور را کامل وارد کنید"); return@setOnClickListener }
            btnLogin.isEnabled = false; toast("در حال ورود...")
            // داخل setOnClickListener:
            repo.login(u, p) { r ->
                runOnUiThread {
                    btnLogin.isEnabled = true
                    if (r.isSuccess) {
                        val token = r.getOrNull()!!
                        toast("ورود موفقیت‌آمیز بود")
                        fetchStatusAndEnter(token, u)
                    } else {
                        val msg = (r.exceptionOrNull()?.message ?: "خطا در ارتباط با سرور")
                        when {
                            msg.contains("Invalid credentials", true) -> toast("نام کاربری یا رمزعبور اشتباه است")
                            msg.contains("Maximum concurrent sessions", true) -> toast("تعداد نشست‌های مجاز بیشتر شده")
                            else -> toast(msg)
                        }
                    }
                }
            }
        }
    }

    private fun fetchStatusAndEnter(token: String, username: String) {
        repo.status(token) { rs ->
            runOnUiThread {
                if (rs.isSuccess) {
                    TokenStore.save(this, token, username)

                    // + ADD: ارسال FCM و بعد برو Main (UX معطل نمی‌شود)
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                        .addOnCompleteListener { t ->
                            if (t.isSuccessful) {
                                val fcm = t.result
                                com.v2ray.ang.net.ApiClient.postUpdateFcmToken(token, fcm) { /* silent */ }
                            }
                            goMain()
                        }

                    // - REMOVE: این goMain() قبلی را حذف کن که دوبار نرویم
                    // goMain()
                } else {
                    toast("زمان نشست منقضی شده، دوباره وارد شوید")
                }
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
