package com.vim.vrcfriends

import com.vim.vrcfriends.ui.RefreshBlock
import com.vim.vrcfriends.ui.RefreshGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshGateTest {

    @Test
    fun firstAttemptAlwaysAllowed() {
        val gate = RefreshGate(minIntervalMs = 30_000, errorCooldownMs = 8_000)
        assertNull(gate.tryStart(1_000))
    }

    @Test
    fun inFlightBlocksWithoutConsumingBudget() {
        val gate = RefreshGate(minIntervalMs = 30_000)
        assertNull(gate.tryStart(0))
        assertEquals(RefreshBlock.IN_FLIGHT, gate.tryStart(1))
        assertEquals(RefreshBlock.IN_FLIGHT, gate.tryStart(299_999))
    }

    @Test
    fun successBlocksForMinIntervalThenAllows() {
        val gate = RefreshGate(minIntervalMs = 30_000)
        assertNull(gate.tryStart(0))
        gate.finish(0, ok = true)

        assertEquals(RefreshBlock.TOO_SOON, gate.tryStart(29_999))
        assertNull("正好 30s 应放行", gate.tryStart(30_000))
    }

    @Test
    fun failureUsesShortCooldown() {
        val gate = RefreshGate(minIntervalMs = 30_000, errorCooldownMs = 8_000)
        assertNull(gate.tryStart(0))
        gate.finish(0, ok = false)

        assertEquals(RefreshBlock.COOLDOWN, gate.tryStart(7_999))
        assertNull(gate.tryStart(8_000))
    }

    @Test
    fun successAfterFailureClearsCooldown() {
        val gate = RefreshGate(minIntervalMs = 30_000, errorCooldownMs = 8_000)
        gate.tryStart(0); gate.finish(0, ok = false)
        assertNull(gate.tryStart(8_000))
        gate.finish(8_000, ok = true)
        assertEquals("成功后回到正常间隔", RefreshBlock.TOO_SOON, gate.tryStart(37_999))
        assertNull(gate.tryStart(38_000))
    }

    @Test
    fun waitSecondsRoundsUp() {
        val gate = RefreshGate(minIntervalMs = 30_000)
        assertEquals(0, gate.waitSeconds(0))
        gate.tryStart(0); gate.finish(0, ok = true)
        assertEquals(30, gate.waitSeconds(0))
        assertEquals(1, gate.waitSeconds(29_001))
        assertEquals(0, gate.waitSeconds(30_000))
    }

    @Test
    fun resetClearsEverything() {
        val gate = RefreshGate(minIntervalMs = 30_000)
        gate.tryStart(0)
        gate.reset()
        assertNull("reset 后不该被在飞状态卡住", gate.tryStart(1))
        assertEquals(0, gate.waitSeconds(1))
    }
}
