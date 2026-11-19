package com.vim.vrcfriends.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 头像加载器：网络 → 磁盘缓存 → 解码（按目标尺寸降采样）→ 内存 LRU。
 *
 * 不引 Coil/Glide（工程规矩：零第三方依赖），所以自己管三件小事：
 * 1. 后台线程池（2 条，够圆表用，也别把 3G/电池榨干）
 * 2. 同一 URL 在飞时复用请求，避免 20 行列表打出 20 个并发
 * 3. 回调一律回主线程，Compose 侧直接写 state 就行
 */
class AvatarLoader(
    private val store: AvatarStore,
    private val maxPx: Int = 96,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MEMORY_BUDGET_KB = 8 * 1024

        /**
         * VRChat 图片 URL 末段是尺寸：`.../file_xxx/1/256`。
         * 圆表上头像只有 22dp，直接要 64px 那份（实测 6.5KB → 3.1KB）。
         */
        fun smallUrl(url: String): String =
            url.trim().replace(Regex("/\\d+$"), "/64")
    }

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "vrc-avatar").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BUDGET_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val inFlight = HashSet<String>()
    private val closed = AtomicBoolean(false)

    /** 内存里已经有就同步回主线程给，省一次线程切换。 */
    fun cached(url: String?): Bitmap? {
        if (!AvatarStore.usableUrl(url)) return null
        return memory.get(smallUrl(url!!))
    }

    /**
     * 异步取图；[onReady] 回主线程，可能为 null（没 URL / 下载失败 / 不是图片）。
     * 同一个 URL 多次调用只发一次请求。
     */
    fun load(url: String?, px: Int, onReady: (Bitmap?) -> Unit) {
        if (!AvatarStore.usableUrl(url)) {
            post { onReady(null) }
            return
        }
        val key = smallUrl(url!!)
        memory.get(key)?.let { post { onReady(it) }; return }

        val started = synchronized(inFlight) {
            if (inFlight.contains(key)) false else { inFlight.add(key); true }
        }
        if (!started) {
            // 已有请求在飞：不排队，直接放弃（列表滚动时会自然重试），
            // 但已经缓存到磁盘的可以立刻拿来用
            val disk = store.read(key)
            val bmp = disk?.let { decode(it, px) }
            if (bmp != null) memory.put(key, bmp)
            post { onReady(bmp) }
            return
        }

        pool.execute {
            val bmp = try {
                fetchAndDecode(key, px)
            } catch (e: Exception) {
                null
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
            if (bmp != null) memory.put(key, bmp)
            post { onReady(bmp) }
        }
    }

    private fun fetchAndDecode(url: String, px: Int): Bitmap? {
        if (closed.get()) return null
        store.read(url)?.let { bytes -> decode(bytes, px)?.let { return it } }

        val bytes = download(url) ?: return null
        store.write(url, bytes)
        return decode(bytes, px)
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "vrc-watch-friends/0.2.0 (WearOS)")
                setRequestProperty("Accept", "image/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val len = conn.contentLengthLong
            if (len > AvatarStore.MAX_ENTRY_BYTES) return null
            val declared = conn.contentType ?: ""
            if (declared.isNotEmpty() && !declared.startsWith("image/", ignoreCase = true)) return null

            val out = ByteArrayOutputStream(16 * 1024)
            val buf = ByteArray(8 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > AvatarStore.MAX_ENTRY_BYTES) return null
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 先量尺寸再按 2 的幂降采样，圆表上只要 96px 以内，不必解全图。 */
    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val target = px.coerceIn(24, maxPx)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
            ?: return null
        // 组合期再 clip(CircleShape) 每帧都要走一次 path 裁剪，GPU 上很贵；
        // 这里一次性做成圆形位图，列表直接画。
        return circular(raw)
    }

    private fun circular(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        if (size <= 0) return src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }

    private fun post(block: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun shutdown() {
        if (closed.compareAndSet(false, true)) pool.shutdownNow()
    }
}
