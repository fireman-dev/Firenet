package com.v2ray.ang.net

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * نسخه‌ی بازنویسی‌شده با Fallback دامین ۵ ثانیه‌ای.
 * همه‌ی درخواست‌ها به جای اتکا به BASE ثابت، از DomainFallback.request استفاده می‌کنند.
 */

data class StatusResponse(
    val username: String? = null,
    val used_traffic: Long? = null,
    val data_limit: Long? = null,
    val expire: Long? = null,
    val status: String? = null,
    val links: List<String>? = null,
    val need_to_update: Boolean? = null,
    val is_ignoreable: Boolean? = null
)

object ApiClient {

    // -------------------------
    // Helpers
    // -------------------------

    private fun jsonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val base = mutableMapOf(
            "Accept" to "application/json",
        )
        base.putAll(extra)
        return base
    }

    private fun bearer(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")

    private fun bytes(body: JSONObject): ByteArray =
        body.toString().toByteArray(StandardCharsets.UTF_8)

    private fun bytesFormUrlEncoded(form: Map<String, String>): ByteArray {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return encoded.toByteArray(StandardCharsets.UTF_8)
    }

    // -------------------------
    // Endpoints
    // -------------------------

    fun postLogin(
        username: String,
        password: String,
        deviceId: String,
        appVersion: String,
        cb: (Result<String>) -> Unit
    ) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("device_id", deviceId)
                    put("app_version", appVersion)
                }
                val res = DomainFallback.request(
                    path = "/api/login",
                    method = "POST",
                    headers = jsonHeaders() + mapOf("Content-Type" to "application/json"),
                    body = bytes(body),
                    contentType = "application/json"
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) {
                            val j = JSONObject(text)
                            val token = j.optString("token", "")
                            if (token.isNotBlank()) cb(Result.success(token))
                            else cb(Result.failure(IllegalStateException("Missing token")))
                        } else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun getStatus(
        token: String,
        cb: (Result<StatusResponse>) -> Unit
    ) {
        Thread {
            try {
                val res = DomainFallback.request(
                    path = "/api/status",
                    method = "GET",
                    headers = jsonHeaders() + bearer(token)
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) {
                            val j = JSONObject(text)

                            val linksArr: JSONArray? = j.optJSONArray("links")
                            val links: List<String>? = linksArr?.let { arr ->
                                List(arr.length()) { idx -> arr.optString(idx) }
                            }

                            val needUpdate: Boolean? = when {
                                j.has("need_to_update") ->
                                    if (j.isNull("need_to_update")) null else j.optBoolean("need_to_update")
                                else -> null
                            }
                            val isIgnoreable: Boolean? = when {
                                j.has("is_ignoreable") ->
                                    if (j.isNull("is_ignoreable")) null else j.optBoolean("is_ignoreable")
                                else -> null
                            }

                            val resp = StatusResponse(
                                username = j.optString("username", null),
                                used_traffic = if (j.isNull("used_traffic")) null else j.optLong("used_traffic"),
                                data_limit = if (j.isNull("data_limit")) null else j.optLong("data_limit"),
                                expire = if (j.isNull("expire")) null else j.optLong("expire"),
                                status = j.optString("status", null),
                                links = links,
                                need_to_update = needUpdate,
                                is_ignoreable = isIgnoreable
                            )
                            cb(Result.success(resp))
                        } else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun postKeepAlive(
        token: String,
        cb: (Result<Unit>) -> Unit
    ) {
        Thread {
            try {
                val res = DomainFallback.request(
                    path = "/api/keep-alive",
                    method = "POST",
                    headers = jsonHeaders() + bearer(token)
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) cb(Result.success(Unit))
                        else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun postUpdateFcmToken(
        token: String,
        fcmToken: String,
        cb: (Result<Unit>) -> Unit
    ) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("fcm_token", fcmToken)
                }

                val res = DomainFallback.request(
                    path = "/api/update-fcm-token",
                    method = "POST",
                    headers = jsonHeaders() + bearer(token) + mapOf("Content-Type" to "application/json"),
                    body = bytes(body),
                    contentType = "application/json"
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) cb(Result.success(Unit))
                        else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun postLogout(
        token: String,
        cb: (Result<Unit>) -> Unit
    ) {
        Thread {
            try {
                val res = DomainFallback.request(
                    path = "/api/logout",
                    method = "POST",
                    headers = jsonHeaders() + bearer(token)
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) cb(Result.success(Unit))
                        else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun postUpdatePromptSeen(
        token: String,
        cb: (Result<Unit>) -> Unit
    ) {
        Thread {
            try {
                val res = DomainFallback.request(
                    path = "/api/update-prompt-seen",
                    method = "POST",
                    headers = jsonHeaders() + bearer(token)
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) cb(Result.success(Unit))
                        else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    // اورلود سازگار با کد قدیمی: فقط نسخه را می‌گیرد و platform را "android" می‌گذارد.
    fun postReportUpdate(
        token: String,
        version: String,
        cb: (Result<Unit>) -> Unit
    ) = postReportUpdate(
        token = token,
        platform = "android",
        version = version,
        cb = cb
    )

    fun postReportUpdate(
        token: String,
        platform: String,
        version: String,
        cb: (Result<Unit>) -> Unit
    ) {
        Thread {
            try {
                val form = mapOf(
                    "platform" to platform,
                    "version" to version
                )
                val res = DomainFallback.request(
                    path = "/api/report-update",
                    method = "POST",
                    headers = jsonHeaders() + bearer(token) + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = bytesFormUrlEncoded(form),
                    contentType = "application/x-www-form-urlencoded"
                )
                res.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) cb(Result.success(Unit))
                        else {
                            val j = runCatching { JSONObject(text) }.getOrNull()
                            val msg = j?.optString("message")?.ifEmpty { j?.optString("error") }
                                ?: "خطای $code"
                            cb(Result.failure(Exception(msg)))
                        }
                    },
                    onFailure = { cb(Result.failure(it)) }
                )
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }
}
