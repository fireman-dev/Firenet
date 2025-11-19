package com.v2ray.ang.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * حمل‌کننده‌ی HTTP با پشتیبانی از چند دامین و Fallback پس از ۵ ثانیه بی‌پاسخی.
 * - روی هر دامین حداکثر ۵ ثانیه صبر می‌کند (readTimeout=5000, connectTimeout=5000).
 * - اگر تایم‌اوت یا خطای شبکه رخ دهد روی دامین بعدی تلاش می‌کند.
 * - در صورت موفقیت، دامین موفق را به ابتدای لیست می‌آورد تا دفعات بعد سریع‌تر پاسخ بدهد.
 */
object DomainFallback {

    /** لیست دامین‌ها. ترتیب اهمیت دارد. مورد اول ارجح است. */
    private val domains = CopyOnWriteArrayList<String>(
        listOf(
            "https://report.soft99.sbs:2053",
            "https://firenet.mapmah2025.workers.dev"
        )
    )

    /** جایگزینی کامل لیست دامین‌ها در زمان اجرا. */
    fun setDomains(newDomains: List<String>) {
        if (newDomains.isNotEmpty()) {
            domains.clear()
            domains.addAll(newDomains)
        }
    }

    /** افزودن دامین پویا در زمان اجرا. */
    fun addDomains(vararg more: String) {
        more.forEach { d ->
            if (d.isNotBlank() && !domains.contains(d)) domains.add(d)
        }
    }

    /** خواندن متن پاسخ از اتصال. */
    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    /**
     * اجرای یک درخواست با Fallback دامین.
     *
     * @param path مسیر نسبی API. مثل "/api/status"
     * @param method "GET" | "POST" | ...
     * @param headers هدرها
     * @param body بدنه‌ی بایت‌ها برای متدهای دارای بدنه
     * @param contentType مقدار هدر Content-Type وقتی بدنه داریم
     * @return یک شیء Result شامل (code, body) در موفقیت یا Exception در خطا
     */
    fun request(
        path: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null
    ): Result<Pair<Int, String>> {
        var lastError: Exception? = null

        // بر روی هر دامین امتحان می‌کنیم
        for (i in domains.indices) {
            val base = domains[i].trimEnd('/')
            val url = URL("$base${if (path.startsWith("/")) path else "/$path"}")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                // تایم‌اوت ۵ ثانیه مطابق نیاز
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val text = readBody(conn)

                // موفقیت: دامین موفق را به ابتدای لیست ببریم
                if (code in 200..299) {
                    // جابجایی دامین موفق به اول
                    promoteDomainToFront(i)
                    conn.disconnect()
                    return Result.success(code to text)
                } else {
                    // خطای منطقی از سرور. اینجا هم می‌توانیم promote کنیم چون رسیدیم به سرور.
                    promoteDomainToFront(i)
                    conn.disconnect()
                    return Result.success(code to text)
                }
            } catch (e: Exception) {
                lastError = e
                // روی دامین بعدی امتحان می‌کنیم
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }

        // اگر هیچ دامینی جواب نداد
        return Result.failure(lastError ?: RuntimeException("No domain responded within 5s each"))
    }

    /** دامین موفق را به ابتدای لیست منتقل می‌کند. */
    private fun promoteDomainToFront(index: Int) {
        if (index <= 0) return
        val copy = domains[index]
        domains.removeAt(index)
        domains.add(0, copy)
    }
}
