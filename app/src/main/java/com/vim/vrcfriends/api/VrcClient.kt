package com.vim.vrcfriends.api

import com.vim.vrcfriends.json.Json
import com.vim.vrcfriends.json.JsonException
import com.vim.vrcfriends.json.JsonParser

class VrcException(val code: Int, message: String) : Exception(message)

data class VrcResponse(
    val code: Int,
    val body: String,
    /** Set-Cookie 头里我们关心的键值（auth / twoFactorAuth）。 */
    val cookies: Map<String, String> = emptyMap(),
)

/** 抽象一层，单测用假实现，真机用 [HttpTransport]。 */
interface VrcTransport {
    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): VrcResponse
}

/** 会话：只存 cookie，不存密码。 */
class VrcSession(var authCookie: String? = null, var twoFactorCookie: String? = null) {
    val isLoggedIn: Boolean get() = !authCookie.isNullOrBlank()

    fun cookieHeader(): String {
        val parts = ArrayList<String>()
        authCookie?.takeIf { it.isNotBlank() }?.let { parts.add("auth=$it") }
        twoFactorCookie?.takeIf { it.isNotBlank() }?.let { parts.add("twoFactorAuth=$it") }
        return parts.joinToString("; ")
    }

    /** 从响应里吸收 cookie，返回是否有变化。 */
    fun absorb(cookies: Map<String, String>): Boolean {
        var changed = false
        cookies["auth"]?.takeIf { it.isNotBlank() }?.let { if (it != authCookie) { authCookie = it; changed = true } }
        cookies["twoFactorAuth"]?.takeIf { it.isNotBlank() }?.let { if (it != twoFactorCookie) { twoFactorCookie = it; changed = true } }
        return changed
    }
}

/**
 * VRChat REST 客户端。
 *
 * 只用公开文档里的老接口（`/api/1/...`），因为手表端只要「在线好友」这一件事：
 * - `GET /api/1/auth/user/friends?offline=false` 在线好友（分页 n=100）
 * - `GET /api/1/favorites?type=friend` 收藏好友（分组 tag）
 * - `GET /api/1/favorites/groups` 收藏分组名
 * - `GET /api/1/worlds/<id>` 世界名
 */
class VrcApi(
    private val transport: VrcTransport,
    private val session: VrcSession,
) {
    companion object {
        const val PAGE = 100
        const val MAX_PAGES = 20
    }

    fun friends(onlineOnly: Boolean = true): List<VrcFriend> = paged(
        base = if (onlineOnly) "/api/1/auth/user/friends?offline=false" else "/api/1/auth/user/friends?offline=true"
    ) { VrcParsing.parseFriends(it) }

    fun favoriteFriends(): Map<String, Set<String>> {
        val pages: List<Map<String, Set<String>>> = paged("/api/1/favorites?type=friend") {
            listOf(VrcParsing.parseFavoriteFriends(it))
        }
        val out = LinkedHashMap<String, Set<String>>()
        for (page in pages) {
            for ((k, v) in page) {
                val merged = out[k]?.toMutableSet() ?: LinkedHashSet()
                merged.addAll(v)
                out[k] = merged
            }
        }
        return out
    }

    fun favoriteGroups(): List<GroupMeta> =
        ok(transport.request("GET", "/api/1/favorites/groups", headers = authHeaders()))
            .let(VrcParsing::parseFavoriteGroups)

    fun worldName(worldId: String): String? {
        if (!worldId.startsWith("wrld_")) return null
        return try {
            ok(transport.request("GET", "/api/1/worlds/$worldId", headers = authHeaders()))
                .let(VrcParsing::parseWorldName)
        } catch (e: VrcException) {
            if (e.code == 404 || e.code == 403) null else throw e
        }
    }

    /**
     * 列表优先：默认**不解析世界名**，先把手上有缓存的立刻返回；
     * 没解析到的世界 ID 放进 [Board.pendingWorlds]，由界面后台补。
     */
    fun fetchBoard(
        worldCache: MutableMap<String, String> = HashMap(),
        resolveWorlds: Boolean = false,
    ): Board {
        // 手表走系统代理，同时开 3 条 TLS 会被掐（实测 SSLHandshakeException: connection closed），
        // 所以顺序拉前两个，且不再请求已下线的 /favorites/groups。
        val t0 = System.currentTimeMillis()
        val friends = friends()
        val t1 = System.currentTimeMillis()
        // 分组数据拿不到时（429/401/网络）：不要把所有好友都藏起来，
        // 退化成「显示全部在线 + 未分组」，并给界面一个提示。
        var favoritesOk = true
        val favorites = if (session.isLoggedIn) {
            runCatching { favoriteFriends() }
                .onFailure { favoritesOk = false }
                .getOrDefault(emptyMap())
        } else emptyMap()
        val t2 = System.currentTimeMillis()
        val groups = emptyList<GroupMeta>()

        val ids = FriendView.worldIdsToResolve(friends, favorites, onlyGrouped = favoritesOk)
        val pending = ids.filter { !worldCache.containsKey(it) }
        if (resolveWorlds && pending.isNotEmpty()) worldCache.putAll(resolveWorlds(pending))

        val rows = FriendView.buildRows(friends, groups, favorites, worldCache, onlyGrouped = favoritesOk)
        android.util.Log.d(
            "vrc-board",
            "friends=${friends.size} favorites=${favorites.size} favOk=$favoritesOk rows=${rows.size} " +
                "friendsMs=${t1 - t0} favMs=${t2 - t1} buildMs=${System.currentTimeMillis() - t2} " +
                "sampleFav=${favorites.entries.firstOrNull()?.key?.take(16)}"
        )
        return Board(
            rows = rows,
            onlineCount = rows.size,
            pendingWorlds = if (resolveWorlds) emptyList() else pending,
            groupedFilter = favoritesOk,
        )
    }

    /** 并发解析世界名（6 路），失败的回落成 ID，不阻塞列表。 */
    fun resolveWorlds(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            pool.invokeAll(ids.map { id ->
                java.util.concurrent.Callable {
                    out[id] = try { worldName(id) ?: id } catch (e: Exception) { id }
                }
            })
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun authHeaders(): Map<String, String> {
        val h = HashMap<String, String>()
        session.cookieHeader().takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    private fun ok(resp: VrcResponse): String {
        if (resp.cookies.isNotEmpty()) session.absorb(resp.cookies)
        if (resp.code !in 200..299) {
            val hint = when (resp.code) {
                401 -> "登录已过期，请重新粘贴 auth cookie"
                403 -> "没有权限（可能是 2FA 未完成）"
                429 -> "请求太频繁，等一会儿再试"
                else -> "HTTP ${resp.code}"
            }
            throw VrcException(resp.code, "$hint: ${resp.body.take(160)}")
        }
        return resp.body
    }

    private fun <T> paged(base: String, parse: (String) -> List<T>): List<T> {
        val sep = if (base.contains('?')) '&' else '?'
        val out = ArrayList<T>()
        for (page in 0 until MAX_PAGES) {
            val path = "$base${sep}n=$PAGE&offset=${page * PAGE}"
            val items = parse(ok(transport.request("GET", path, headers = authHeaders())))
            out.addAll(items)
            if (items.size < PAGE) return out
        }
        return out
    }

}

/** 一次抓取的成品：已过滤、已排序、已带世界名。 */
data class Board(
    val rows: List<FriendRow>,
    val onlineCount: Int,
    val pendingWorlds: List<String> = emptyList(),
    val groupedFilter: Boolean = true,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** 世界名解析完成后替换显示文本（保持顺序不变）。 */
    fun withWorlds(names: Map<String, String>): Board {
        if (names.isEmpty()) return this
        return copy(rows = rows.map { r ->
            val id = r.worldId
            val name = if (id != null) names[id] else null
            if (name != null) r.copy(worldLabel = name) else r
        })
    }
}

/**
 * 登录。
 *
 * VRChat 实测行为（2026-11-19）：
 *  - 登录 = `GET /auth/user` + Basic Auth（POST 会 405）；
 *  - 账号开了 2FA 时 **HTTP 仍是 200**，但 body 是 `{"requiresTwoFactorAuth":["emailOtp"]}`
 *    （也有账号是 `["totp","otp"]`）——所以“200 就是登录成功”是错的，必须看 body；
 *  - 验证码提交：`POST /auth/twofactorauth/totp/verify` 或 `/emailotp/verify`。
 */
class VrcAuth(private val transport: VrcTransport, private val session: VrcSession) {

    data class LoginResult(val ok: Boolean, val twoFactor: List<String> = emptyList())

    fun loginWithCookie(auth: String) {
        session.authCookie = auth.trim()
    }

    /** 用账号密码登录。返回 ok，或需要哪些 2FA 方式。 */
    fun login(username: String, password: String): LoginResult {
        val basic = "Basic " + base64("$username:$password")
        val resp = transport.request(
            "GET",
            "/api/1/auth/user",
            headers = mapOf("Authorization" to basic),
        )
        session.absorb(resp.cookies)

        val twoFactor = parseTwoFactor(resp.body)
        if (twoFactor.isNotEmpty()) return LoginResult(ok = false, twoFactor = twoFactor)

        if (resp.code !in 200..299) throw VrcException(resp.code, "登录失败: ${resp.body.take(160)}")
        if (!isRealUser(resp.body)) throw VrcException(resp.code, "登录失败: ${resp.body.take(160)}")
        return LoginResult(ok = true)
    }

    /** 提交验证码；成功后再用 cookie 拉一次 /auth/user 确认。 */
    fun verifyTwoFactor(code: String, method: String): Boolean {
        val path = if (method.equals("emailOtp", ignoreCase = true)) {
            "/api/1/auth/twofactorauth/emailotp/verify"
        } else {
            "/api/1/auth/twofactorauth/totp/verify"
        }
        val verify = transport.request(
            "POST",
            path,
            body = """{"code":"${code.trim()}"}""",
            headers = mapOf("Cookie" to session.cookieHeader(), "Content-Type" to "application/json"),
        )
        session.absorb(verify.cookies)
        val parsed = runCatching { JsonParser.parse(verify.body) }.getOrNull()
        if ((parsed?.get("verified") as? Json.Bool)?.value != true) return false

        val me = transport.request("GET", "/api/1/auth/user", headers = mapOf("Cookie" to session.cookieHeader()))
        session.absorb(me.cookies)
        return me.code in 200..299 && isRealUser(me.body)
    }

    /** 会话是否真的有效（body 是用户对象，而不是 2FA 挑战）。 */
    fun verifySession(): Boolean = try {
        val resp = transport.request("GET", "/api/1/auth/user")
        session.absorb(resp.cookies)
        resp.code in 200..299 && isRealUser(resp.body)
    } catch (e: Exception) {
        false
    }

    companion object {
        private val TWO_FACTOR = Regex("requiresTwoFactorAuth\"\\s*:\\s*\\[([^]]*)]")
        private val QUOTED = Regex("\"([^\"]+)\"")

        fun parseTwoFactor(body: String): List<String> {
            val m = TWO_FACTOR.find(body) ?: return emptyList()
            return QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        }

        fun isRealUser(body: String): Boolean =
            body.contains("\"id\":\"usr_") || body.contains("\"id\": \"usr_")
    }

    private fun base64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
