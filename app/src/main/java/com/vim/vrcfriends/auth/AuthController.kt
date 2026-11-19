package com.vim.vrcfriends.auth

import com.vim.vrcfriends.api.VrcAuth
import com.vim.vrcfriends.api.VrcException
import com.vim.vrcfriends.api.VrcSession
import com.vim.vrcfriends.api.VrcTransport

/** 凭据存储的最小接口（Android 实现用 Keystore；单测用内存实现）。 */
interface CredStore {
    fun load(): CredentialStore.Creds?
    fun save(username: String, password: String)
    fun clear()
}

/**
 * 登录状态机（纯逻辑，不碰 UI / Android，便于单测）。
 *
 * 规则（兴逸 2026-11-19）：
 *  1. 有保存的 cookie → 先用它；`GET /auth/user` 返回真实用户对象才算有效。
 *  2. cookie 失效/不存在 → 用保存的用户名密码登录；服务端要求 2FA 时进入「等验证码」。
 *  3. 没有保存的凭据 → 要求用户输入；**输一次就存**（表上打字太贵，失败也存）。
 */
class AuthController(
    private val transport: VrcTransport,
    private val session: VrcSession,
    private val creds: CredStore,
) {

    sealed class State {
        data object NeedLogin : State()
        data class NeedTotp(val hint: String = "输入验证码", val method: String = "totp") : State()
        data object Ready : State()
        data class Error(val message: String, val retryLogin: Boolean = false) : State()
    }

    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var pendingMethod: String = "totp"

    private val auth get() = VrcAuth(transport, session)

    /** 启动时调用：cookie → 保存的账号密码 → 要求输入。 */
    fun start(): State {
        // 有 cookie 就直接用：省掉启动时的一次验证请求（手表上一轮要 5s+）。
        // 真失效时第一页拉取会 401，由 onUnauthorized() 收口。
        if (session.isLoggedIn) return State.Ready
        val saved = creds.load() ?: return State.NeedLogin
        val state = login(saved.username, saved.password)
        // 保存的凭据失效：回登录页让用户改，而不是停在错误页
        return if (state is State.Error) State.NeedLogin else state
    }

    fun login(user: String, pass: String): State {
        if (user.isBlank() || pass.isBlank()) return State.Error("用户名和密码都要填", retryLogin = true)
        pendingUser = user
        pendingPass = pass
        // 手表打字成本高：先落盘，登录失败也不丢
        creds.save(user, pass)

        return try {
            val result = auth.login(user, pass)
            if (result.ok) {
                State.Ready
            } else {
                pendingMethod = pickMethod(result.twoFactor)
                State.NeedTotp(hintFor(result.twoFactor), pendingMethod)
            }
        } catch (e: VrcException) {
            State.Error(
                when (e.code) {
                    401 -> "用户名或密码错误"
                    429 -> "请求太频繁（429），等 1–2 分钟再登录"
                    else -> "登录失败：${e.message}"
                },
                retryLogin = true
            )
        } catch (e: Exception) {
            State.Error("网络错误：${e.message ?: e.javaClass.simpleName}", retryLogin = true)
        }
    }

    fun submitTotp(code: String): State {
        if (pendingUser == null || pendingPass == null) {
            return State.Error("登录状态丢失，请重新登录", retryLogin = true)
        }
        val clean = code.filter { it.isDigit() }
        if (clean.length !in 6..8) return State.NeedTotp("验证码是 6 位数字", pendingMethod)

        return try {
            if (!auth.verifyTwoFactor(clean, pendingMethod)) {
                State.NeedTotp("验证码不对，再试一次", pendingMethod)
            } else {
                State.Ready
            }
        } catch (e: VrcException) {
            when (e.code) {
                401 -> State.NeedTotp("验证码不对，再试一次", pendingMethod)
                429 -> State.Error("请求太频繁（429），等 1–2 分钟再试", retryLogin = true)
                else -> State.Error("验证失败：${e.message}", retryLogin = true)
            }
        } catch (e: Exception) {
            State.Error("网络错误：${e.message ?: e.javaClass.simpleName}", retryLogin = true)
        }
    }

    /** 运行中遇到 401：清 cookie，尝试自动重登，必要时回到验证码页。 */
    fun onUnauthorized(): State {
        session.authCookie = null
        session.twoFactorCookie = null
        val saved = creds.load() ?: return State.NeedLogin
        val state = login(saved.username, saved.password)
        return if (state is State.Error) State.NeedLogin else state
    }

    fun logout() {
        creds.clear()
        session.authCookie = null
        session.twoFactorCookie = null
        pendingUser = null
        pendingPass = null
    }

    fun savedUsername(): String? = creds.load()?.username

    /**
     * 选择验证方式：优先 totp（验证器 App），没有就 emailOtp（邮件验证码）。
     * `otp` 是备用恢复码，不作为默认入口。
     */
    private fun pickMethod(methods: List<String>): String =
        when {
            methods.any { it.equals("totp", true) } -> "totp"
            methods.any { it.equals("emailOtp", true) } -> "emailOtp"
            methods.any { it.equals("otp", true) } -> "otp"
            else -> "totp"
        }

    private fun hintFor(methods: List<String>): String = when (pickMethod(methods)) {
        "emailOtp" -> "验证码已发到邮箱，输入 6 位数字"
        "otp" -> "输入一次性恢复码"
        else -> "打开身份验证器 App，输入 6 位数字"
    }
}
