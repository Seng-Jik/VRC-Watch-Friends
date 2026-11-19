package com.vim.vrcfriends

import com.vim.vrcfriends.api.VrcAuth
import com.vim.vrcfriends.api.VrcResponse
import com.vim.vrcfriends.api.VrcSession
import com.vim.vrcfriends.api.VrcTransport
import com.vim.vrcfriends.auth.AuthController
import com.vim.vrcfriends.auth.CredStore
import com.vim.vrcfriends.auth.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthControllerTest {

    private class FakeTransport : VrcTransport {
        val calls = mutableListOf<String>()
        val routes = mutableMapOf<String, () -> VrcResponse>()

        fun on(method: String, path: String, authed: Boolean = false, block: () -> VrcResponse) {
            routes["$method $path" + if (authed) " +auth" else ""] = block
        }

        override fun request(
            method: String,
            path: String,
            body: String?,
            headers: Map<String, String>,
        ): VrcResponse {
            val authed = headers.keys.any { it.equals("Authorization", ignoreCase = true) }
            val key = "$method $path" + if (authed) " +auth" else ""
            calls += key
            return routes[key]?.invoke() ?: VrcResponse(404, "{}")
        }
    }

    private class MemCreds(var user: String? = null, var pass: String? = null) : CredStore {
        var saved = 0
        override fun load(): CredentialStore.Creds? =
            if (user != null && pass != null) CredentialStore.Creds(user!!, pass!!) else null

        override fun save(username: String, password: String) {
            user = username
            pass = password
            saved++
        }

        override fun clear() {
            user = null
            pass = null
        }
    }

    private fun controller(t: FakeTransport, s: VrcSession, c: CredStore) = AuthController(t, s, c)

    private val userJson = """{"id":"usr_123","displayName":"me"}"""
    private val emailOtpJson = """{"requiresTwoFactorAuth":["emailOtp"]}"""
    private val totpJson = """{"requiresTwoFactorAuth":["totp","otp"]}"""

    @Test
    fun validCookieGoesStraightIn() {
        val t = FakeTransport()
        val c = MemCreds()
        assertEquals(AuthController.State.Ready, controller(t, VrcSession("good"), c).start())
        assertEquals(0, c.saved)
        assertTrue(t.calls.isEmpty()) // 启动不再先打一次 /auth/user
    }

    @Test
    fun twoFactorChallengeWithHttp200IsNotTreatedAsLoggedIn() {
        // 实测坑：200 + requiresTwoFactorAuth。cookie 阶段直接放行，
        // 但列表请求 401 后必须重新登录并识别出 2FA，而不是反复重试。
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, emailOtpJson) }
        val state = controller(t, VrcSession("stale-cookie"), MemCreds("me", "pw")).onUnauthorized()
        assertTrue(state is AuthController.State.NeedTotp)
        assertEquals("emailOtp", (state as AuthController.State.NeedTotp).method)
    }

    @Test
    fun deadCookieFallsBackToSavedCredentials() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user") { VrcResponse(401, "{}") }
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, userJson) }
        val s = VrcSession("dead")
        assertEquals(AuthController.State.Ready, controller(t, s, MemCreds("me", "pw")).start())
    }

    @Test
    fun noCredentialsAsksForLogin() {
        val t = FakeTransport()
        assertEquals(AuthController.State.NeedLogin, controller(t, VrcSession(), MemCreds()).start())
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun emailOtpIsRequestedAndVerified() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, emailOtpJson) }
        t.on("POST", "/api/1/auth/twofactorauth/emailotp/verify") { VrcResponse(200, """{"verified":true}""") }
        t.on("GET", "/api/1/auth/user") { VrcResponse(200, userJson) }
        val c = MemCreds()
        val ctl = controller(t, VrcSession(), c)

        val first = ctl.login("me", "pw")
        assertTrue(first is AuthController.State.NeedTotp)
        assertEquals("emailOtp", (first as AuthController.State.NeedTotp).method)
        assertTrue(first.hint.contains("邮箱"))

        assertEquals(AuthController.State.Ready, ctl.submitTotp("123456"))
        assertEquals(1, c.saved)
    }

    @Test
    fun totpIsPreferredWhenBothOffered() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, totpJson) }
        val state = controller(t, VrcSession(), MemCreds()).login("me", "pw")
        assertTrue(state is AuthController.State.NeedTotp)
        assertEquals("totp", (state as AuthController.State.NeedTotp).method)
    }

    @Test
    fun wrongCodeStaysOnTotpScreen() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, totpJson) }
        t.on("POST", "/api/1/auth/twofactorauth/totp/verify") { VrcResponse(200, """{"verified":false}""") }
        val ctl = controller(t, VrcSession(), MemCreds())
        assertTrue(ctl.login("me", "pw") is AuthController.State.NeedTotp)
        assertTrue(ctl.submitTotp("000000") is AuthController.State.NeedTotp)
    }

    @Test
    fun badPasswordReportsErrorButStillSavesCredentials() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(401, """{"error":{"message":"bad"}}""") }
        val c = MemCreds()
        val state = controller(t, VrcSession(), c).login("me", "nope")
        assertTrue(state is AuthController.State.Error)
        assertEquals(1, c.saved)
        assertNotNull(c.load())
        assertEquals("me", c.load()!!.username)
    }

    @Test
    fun savedCredsThatFailReturnToLoginInsteadOfErrorLoop() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(401, """{"error":{"message":"bad"}}""") }
        assertEquals(AuthController.State.NeedLogin, controller(t, VrcSession(), MemCreds("me", "stale")).start())
    }

    @Test
    fun unauthorizedDuringSessionClearsCookieAndRelogs() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user") { VrcResponse(401, "{}") }
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(200, userJson) }
        val s = VrcSession("stale")
        assertEquals(AuthController.State.Ready, controller(t, s, MemCreds("me", "pw")).onUnauthorized())
    }

    @Test
    fun rateLimitedLoginGivesReadableMessage() {
        val t = FakeTransport()
        t.on("GET", "/api/1/auth/user", authed = true) { VrcResponse(429, """{"error":{"message":"Too many"}}""") }
        val state = controller(t, VrcSession(), MemCreds()).login("me", "pw")
        assertTrue(state is AuthController.State.Error)
        assertTrue((state as AuthController.State.Error).message.contains("429"))
    }

    @Test
    fun logoutClearsEverything() {
        val t = FakeTransport()
        val s = VrcSession("cookie")
        val c = MemCreds("me", "pw")
        controller(t, s, c).logout()
        assertNull(s.authCookie)
        assertNull(c.load())
    }

    @Test
    fun parseTwoFactorReadsBothShapes() {
        assertEquals(listOf("emailOtp"), VrcAuth.parseTwoFactor(emailOtpJson))
        assertEquals(listOf("totp", "otp"), VrcAuth.parseTwoFactor(totpJson))
        assertTrue(VrcAuth.parseTwoFactor("""{"id":"usr_1"}""").isEmpty())
        assertTrue(VrcAuth.isRealUser(userJson))
        assertTrue(!VrcAuth.isRealUser(emailOtpJson))
    }
}
