package com.vim.vrcfriends.image

import java.io.File
import java.security.MessageDigest

/**
 * 头像磁盘缓存（纯 JVM，可单测；不引第三方图片库）。
 *
 * 设计取舍：
 * - 文件名 = URL 的 sha256，URL 里的查询串一起算进去（VRChat 的 CDN 链接带签名参数，
 *   换了参数就是新资源，不能撞 key）。
 * - 只收**看起来真是图片**的字节（JPEG/PNG/WebP 魔数），避免把 HTML 错误页缓存成"头像"。
 * - 写入走 tmp + rename，断电/杀进程不会留下半张图。
 * - 超过 [maxBytes] 按 lastModified 最旧优先删（读取/命中时 [touch] 刷新，形成 LRU）。
 */
class AvatarStore(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
) {

    companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 512L * 1024
        private val HEX = "0123456789abcdef".toCharArray()

        /** 只认 http/https，其它（空、data:、file:）一律不碰网络也不落盘。 */
        fun usableUrl(url: String?): Boolean {
            val u = url?.trim() ?: return false
            if (u.isEmpty()) return false
            return u.startsWith("https://", ignoreCase = true) || u.startsWith("http://", ignoreCase = true)
        }

        /** 图片魔数判定：JPEG / PNG / WebP / GIF。 */
        fun looksLikeImage(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            if (jpeg) return true
            val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            if (png) return true
            val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            if (webp) return true
            val gif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
            return gif
        }

        fun keyOf(url: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(url.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }
    }

    fun fileFor(url: String?): File? {
        if (!usableUrl(url)) return null
        return File(dir, keyOf(url!!.trim()) + ".img")
    }

    fun read(url: String?, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val f = fileFor(url) ?: return null
        if (!f.isFile) return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (!looksLikeImage(bytes)) {
            runCatching { f.delete() }
            return null
        }
        // 命中即刷新时间戳：既是 LRU 依据，也顺手把"读不动"的坏文件排除
        runCatching { f.setLastModified(nowMs) }
        return bytes
    }

    /** 返回 true = 已落盘。太大 / 不是图片 / 写失败 → false（调用方当作没有缓存）。 */
    fun write(url: String?, bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        val f = fileFor(url) ?: return false
        if (bytes.isEmpty() || bytes.size > maxEntryBytes) return false
        if (!looksLikeImage(bytes)) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, f.name + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (f.exists() && !f.delete()) return false
            val ok = tmp.renameTo(f)
            if (!ok) {
                runCatching { tmp.delete() }
                return false
            }
            runCatching { f.setLastModified(nowMs) }
            prune()
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** 超预算就删最旧的，返回删除个数。 */
    fun prune(): Int {
        if (!dir.isDirectory) return 0
        val files = (dir.listFiles() ?: return 0).filter { it.isFile }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        var deleted = 0
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                deleted++
            }
        }
        return deleted
    }

    fun clear(): Int {
        if (!dir.isDirectory) return 0
        var n = 0
        for (f in (dir.listFiles() ?: return 0)) {
            if (f.isFile && runCatching { f.delete() }.getOrDefault(false)) n++
        }
        return n
    }

    fun totalBytes(): Long {
        if (!dir.isDirectory) return 0L
        return (dir.listFiles() ?: return 0L).filter { it.isFile }.sumOf { it.length() }
    }
}
