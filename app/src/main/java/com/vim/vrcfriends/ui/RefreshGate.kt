package com.vim.vrcfriends.ui

/** 刷新被拒绝的原因（UI 用来给不同提示）。 */
enum class RefreshBlock { IN_FLIGHT, TOO_SOON, COOLDOWN }

/**
 * 刷新节流（纯逻辑，可单测）。
 *
 * 目的：圆表上那个 ↻ 很容易被连点，而 VRChat 对 API 有速率限制（429）；
 * 同时也避免"失败-重试-再失败"把网络和电量烧掉。
 * - 成功一次后 [minIntervalMs] 内不许再拉
 * - 失败后只等 [errorCooldownMs]（失败要能尽快重试，但别死循环）
 * - 请求在飞时一律拒绝（不排队，不叠加）
 */
class RefreshGate(
    private val minIntervalMs: Long = 30_000L,
    private val errorCooldownMs: Long = 8_000L,
) {

    private var inFlight = false
    private var nextAllowedAt = Long.MIN_VALUE
    private var lastFailed = false

    val isInFlight: Boolean get() = inFlight

    /** null = 放行（并已标记在飞）；否则为被拒原因。 */
    fun tryStart(nowMs: Long): RefreshBlock? {
        if (inFlight) return RefreshBlock.IN_FLIGHT
        if (nowMs < nextAllowedAt) {
            return if (lastFailed) RefreshBlock.COOLDOWN else RefreshBlock.TOO_SOON
        }
        inFlight = true
        return null
    }

    fun finish(nowMs: Long, ok: Boolean) {
        inFlight = false
        lastFailed = !ok
        nextAllowedAt = nowMs + if (ok) minIntervalMs else errorCooldownMs
    }

    /** 还要等几秒（向上取整），0 = 现在就能刷。 */
    fun waitSeconds(nowMs: Long): Int =
        if (nowMs >= nextAllowedAt) 0 else ((nextAllowedAt - nowMs + 999) / 1000).toInt()

    fun reset() {
        inFlight = false
        lastFailed = false
        nextAllowedAt = Long.MIN_VALUE
    }
}
