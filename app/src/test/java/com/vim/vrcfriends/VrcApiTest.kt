package com.vim.vrcfriends

import com.vim.vrcfriends.api.VrcApi
import com.vim.vrcfriends.api.VrcException
import com.vim.vrcfriends.api.VrcResponse
import com.vim.vrcfriends.api.VrcSession
import com.vim.vrcfriends.api.VrcTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrcApiTest {

    private class FakeTransport(
        private val routes: Map<String, VrcResponse>,
    ) : VrcTransport {
        val calls = ArrayList<Pair<String, String>>()
        override fun request(
            method: String,
            path: String,
            body: String?,
            headers: Map<String, String>,
        ): VrcResponse {
            calls.add(method to path)
            return routes[path] ?: VrcResponse(404, """{"error":"not found"}""")
        }
    }

    private fun friendJson(id: String, loc: String = "wrld_abc:1"): String =
        """{"id":"$id","displayName":"$id","status":"active","location":"$loc"}"""

    @Test
    fun friendsArePagedUntilShortPage() {
        val page0 = (0 until 100).joinToString(",", "[", "]") { friendJson("f$it") }
        val page1 = (100 until 130).joinToString(",", "[", "]") { friendJson("f$it") }
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to VrcResponse(200, page0),
                "/api/1/auth/user/friends?offline=false&n=100&offset=100" to VrcResponse(200, page1),
            )
        )
        val api = VrcApi(t, VrcSession("cookie"))
        val friends = api.friends()
        assertEquals(130, friends.size)
        assertEquals(2, t.calls.size)
        assertTrue(t.calls[1].second.endsWith("offset=100"))
    }

    @Test
    fun unauthorizedSurfacesFriendlyMessage() {
        val t = FakeTransport(
            mapOf("/api/1/auth/user/friends?offline=false&n=100&offset=0" to VrcResponse(401, "Unauthorized"))
        )
        val api = VrcApi(t, VrcSession("stale"))
        val e = runCatching { api.friends() }.exceptionOrNull()
        assertTrue(e is VrcException)
        assertTrue((e as VrcException).message!!.contains("auth cookie"))
    }

    @Test
    fun missingWorldIsNotFatalAndFallsBackToId() {
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to
                        VrcResponse(200, "[${friendJson("f1", loc = "wrld_gone:1")}]"),
                "/api/1/favorites?type=friend&n=100&offset=0" to
                        VrcResponse(200, """[{"id":"f1","tags":["group_0"]}]"""),
                "/api/1/favorites/groups" to VrcResponse(200, "[]"),
                "/api/1/worlds/wrld_gone" to VrcResponse(404, "{}"),
            )
        )
        val api = VrcApi(t, VrcSession("cookie"))
        val board = api.fetchBoard(resolveWorlds = true)
        assertEquals(1, board.rows.size)
        assertEquals("wrld_gone", board.rows[0].worldLabel)
    }

    @Test
    fun worldNamesAreCachedBetweenRefreshes() {
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to
                        VrcResponse(200, "[${friendJson("f1", loc = "wrld_ok:1")}]"),
                "/api/1/favorites?type=friend&n=100&offset=0" to
                        VrcResponse(200, """[{"id":"f1","tags":["group_0"]}]"""),
                "/api/1/favorites/groups" to VrcResponse(200, "[]"),
                "/api/1/worlds/wrld_ok" to VrcResponse(200, """{"name":"Pug"}"""),
            )
        )
        val api = VrcApi(t, VrcSession("cookie"))
        val cache = HashMap<String, String>()
        assertEquals("Pug", api.fetchBoard(cache, resolveWorlds = true).rows[0].worldLabel)
        assertEquals("Pug", api.fetchBoard(cache, resolveWorlds = true).rows[0].worldLabel)
        assertEquals(1, t.calls.count { it.second == "/api/1/worlds/wrld_ok" })
    }

    @Test
    fun fetchBoardAppliesFavoritesAndGroups() {
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to VrcResponse(
                    200,
                    "[${friendJson("on1")},${friendJson("off1", loc = "offline")}]"
                ),
                "/api/1/favorites?type=friend&n=100&offset=0" to
                        VrcResponse(200, """[{"id":"on1","tags":["group_1"]}]"""),
                "/api/1/favorites/groups" to
                        VrcResponse(200, """[{"displayName":"同事","tags":["group_1"],"type":"friend"}]"""),
                "/api/1/worlds/wrld_abc" to VrcResponse(200, """{"name":"Home"}"""),
            )
        )
        val api = VrcApi(t, VrcSession("cookie"))
        val board = api.fetchBoard(resolveWorlds = true)
        assertEquals(1, board.onlineCount)
        assertEquals(1, board.rows.size)
        assertEquals("组2", board.rows[0].groupLabel) // /favorites/groups 已下线，回落组N
        assertEquals("Home", board.rows[0].worldLabel)
    }

    @Test
    fun groupFetchFailureDoesNotBreakBoard() {
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to
                        VrcResponse(200, "[${friendJson("on1")}]"),
                "/api/1/favorites?type=friend&n=100&offset=0" to
                        VrcResponse(200, """[{"id":"on1","tags":["group_1"]}]"""),
                "/api/1/favorites/groups" to VrcResponse(500, "boom"),
                "/api/1/worlds/wrld_abc" to VrcResponse(200, """{"name":"Home"}"""),
            )
        )
        val api = VrcApi(t, VrcSession("cookie"))
        val board = api.fetchBoard()
        assertEquals(1, board.rows.size)
        assertEquals("组2", board.rows[0].groupLabel)
    }

    @Test
    fun sessionCookieHeaderOnlyIncludesPresentValues() {
        val s = VrcSession()
        assertEquals("", s.cookieHeader())
        assertFalse(s.isLoggedIn)
        s.absorb(mapOf("auth" to "A", "twoFactorAuth" to "T"))
        assertTrue(s.isLoggedIn)
        assertEquals("auth=A; twoFactorAuth=T", s.cookieHeader())
    }

    @Test
    fun cookieIsAbsorbedFromResponse() {
        val session = VrcSession()
        val t = FakeTransport(
            mapOf(
                "/api/1/auth/user/friends?offline=false&n=100&offset=0" to
                        VrcResponse(200, "[]", cookies = mapOf("auth" to "NEW"))
            )
        )
        VrcApi(t, session).friends()
        assertEquals("NEW", session.authCookie)
    }
}
