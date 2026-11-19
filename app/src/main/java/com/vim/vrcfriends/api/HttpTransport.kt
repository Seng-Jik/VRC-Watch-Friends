package com.vim.vrcfriends.api

import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

/**
 * 真机用的传输层：`HttpURLConnection`，零第三方依赖。
 * 超时故意压得短（手表网络差、界面要快失败而不是转圈）。
 */
class HttpTransport(
    private val session: VrcSession,
    private val baseUrl: String = "https://api.vrchat.cloud",
) : VrcTransport {

    companion object {
        private const val TAG = "vrc-http"
        const val USER_AGENT = "vrc-watch-friends/0.1.0 (WearOS; contact: local)"
        // 手表走系统代理时首包经常要 10s+，超时给宽一点，失败宁可慢也不要假失败
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    override fun request(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
    ): VrcResponse = try {
        once(method, path, body, headers)
    } catch (e: Exception) {
        // 手表代理偶尔掐掉 TLS 首握（connection closed / EOF）——一次退避重试足够
        Log.w(TAG, "$method $path retry after ${e.javaClass.simpleName}")
        Thread.sleep(350)
        once(method, path, body, headers)
    }

    private fun once(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
    ): VrcResponse {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                session.cookieHeader().takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Log.d(TAG, "$method $path -> $code " + text.take(160).replace('\n', ' '))
            return VrcResponse(code, text, parseSetCookie(conn.headerFields))
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSetCookie(fields: Map<String, List<String>>): Map<String, String> {
        val out = HashMap<String, String>()
        val raw = fields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value ?: return out
        for (line in raw) {
            val first = line.substringBefore(';')
            val name = first.substringBefore('=', "").trim()
            val value = first.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name == "auth" || name == "twoFactorAuth") out[name] = value
        }
        return out
    }
}
