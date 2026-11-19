package com.vim.vrcfriends

import com.vim.vrcfriends.image.AvatarStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AvatarStoreTest {

    private lateinit var dir: File

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x01, 0x02, 0x03, 0x04,
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    private val webp = "RIFF1234WEBPVP8 ".toByteArray(Charsets.US_ASCII)
    private val html = "<!doctype html><html>404</html>".toByteArray(Charsets.US_ASCII)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("avatars").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun usableUrlOnlyHttpAndHttps() {
        assertTrue(AvatarStore.usableUrl("https://api.vrchat.cloud/x.png"))
        assertTrue(AvatarStore.usableUrl("http://cdn/x.png"))
        assertTrue(AvatarStore.usableUrl("  https://a/b.jpg  "))
        assertFalse(AvatarStore.usableUrl(null))
        assertFalse(AvatarStore.usableUrl(""))
        assertFalse(AvatarStore.usableUrl("   "))
        assertFalse(AvatarStore.usableUrl("file:///sdcard/x.png"))
        assertFalse(AvatarStore.usableUrl("data:image/png;base64,AAAA"))
    }

    @Test
    fun recognizesImageMagic() {
        assertTrue(AvatarStore.looksLikeImage(png))
        assertTrue(AvatarStore.looksLikeImage(jpeg))
        assertTrue(AvatarStore.looksLikeImage(webp))
        assertFalse(AvatarStore.looksLikeImage(html))
        assertFalse(AvatarStore.looksLikeImage(byteArrayOf(1, 2, 3)))
        assertFalse(AvatarStore.looksLikeImage(ByteArray(0)))
    }

    @Test
    fun writeThenReadRoundTrip() {
        val store = AvatarStore(dir)
        val url = "https://api.vrchat.cloud/thumb/a.png"
        assertTrue(store.write(url, png, nowMs = 1_000))
        assertEquals(png.toList(), store.read(url)?.toList())
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".img") })
        assertTrue("不应残留 tmp", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun rejectsHtmlAndOversizedAndBadUrl() {
        val store = AvatarStore(dir, maxEntryBytes = 1024)
        assertFalse(store.write("https://a/x", html))
        assertFalse(store.write("file:///x", png))
        assertFalse(store.write(null, png))
        assertFalse("超过单张上限不落盘", store.write("https://a/big", ByteArray(2048) { 0x89.toByte() }))
        assertEquals(0, store.totalBytes())
    }

    @Test
    fun differentQueryStringIsDifferentKey() {
        val store = AvatarStore(dir)
        val a = "https://cdn/av.png?sig=1"
        val b = "https://cdn/av.png?sig=2"
        assertTrue(store.write(a, png))
        assertNull("换签名参数应视为新资源", store.read(b))
        assertTrue(store.write(b, jpeg))
        assertEquals(2, dir.listFiles()!!.count { it.name.endsWith(".img") })
    }

    @Test
    fun corruptCachedFileIsDropped() {
        val store = AvatarStore(dir)
        val url = "https://a/x.png"
        assertTrue(store.write(url, png))
        store.fileFor(url)!!.writeBytes(html)
        assertNull(store.read(url))
        assertFalse("坏文件应被删掉", store.fileFor(url)!!.exists())
    }

    @Test
    fun pruneKeepsBudgetAndDeletesOldestFirst() {
        val store = AvatarStore(dir, maxEntryBytes = 4096)
        val urls = (1..5).map { "https://a/$it.png" }
        urls.forEachIndexed { i, u -> assertTrue(store.write(u, png, nowMs = 1_000L + i)) }

        val budget = AvatarStore(dir, maxBytes = png.size * 3L)
        val deleted = budget.prune()
        assertEquals("删到只剩 3 张", 2, deleted)
        assertEquals(png.size * 3L, budget.totalBytes())
        // 最旧的两张先走
        assertNull(budget.read(urls[0]))
        assertNull(budget.read(urls[1]))
        assertEquals(png.toList(), budget.read(urls[4])?.toList())
    }

    @Test
    fun readTouchesTimestampForLru() {
        val store = AvatarStore(dir)
        val old = "https://a/old.png"
        val keep = "https://a/keep.png"
        assertTrue(store.write(old, png, nowMs = 1_000))
        assertTrue(store.write(keep, png, nowMs = 2_000))
        store.read(old, nowMs = 9_000)
        assertTrue(store.fileFor(old)!!.lastModified() >= store.fileFor(keep)!!.lastModified())

        AvatarStore(dir, maxBytes = png.size.toLong()).prune()
        assertTrue("刚读过的应该留下", store.fileFor(old)!!.exists())
        assertFalse(store.fileFor(keep)!!.exists())
    }

    @Test
    fun writePrunesAutomatically() {
        val store = AvatarStore(dir, maxBytes = png.size * 2L, maxEntryBytes = 4096)
        (1..4).forEach { i -> assertTrue(store.write("https://a/$i.png", png, nowMs = 1_000L + i)) }
        assertTrue("写满后自动裁到预算内", store.totalBytes() <= png.size * 2L)
    }

    @Test
    fun clearRemovesEverything() {
        val store = AvatarStore(dir)
        store.write("https://a/1.png", png)
        store.write("https://a/2.png", png)
        assertEquals(2, store.clear())
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun missingDirIsHarmless() {
        val store = AvatarStore(File(dir, "nope/deeper"))
        assertNull(store.read("https://a/x.png"))
        assertEquals(0, store.prune())
        assertEquals(0L, store.totalBytes())
        assertTrue("write 会自己建目录", store.write("https://a/y.png", png))
    }
}
