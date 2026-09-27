package jp.tasklock.core

import jp.tasklock.core.lock.BlockGate
import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.model.UnlockGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BlockGateTest {
    private val own = "jp.tasklock.app"
    private val now = Instant.parse("2026-09-26T03:00:00Z")
    private val noExempt = { emptySet<String>() }
    private val locked = BlockSnapshot(setOf("com.sns"), null)

    @Test
    fun `sns opened before load is blocked as soon as snapshot loads`() {
        val gate = BlockGate(own)
        assertFalse(gate.isLoaded)
        assertFalse(gate.onWindowEvent("com.sns", now, 0, noExempt)) // 未ロード中はまだ判定できない
        assertEquals("com.sns", gate.onSnapshot(locked, now, 50, noExempt)) // ロード完了で即ブロック
        assertTrue(gate.isLoaded)
    }

    @Test
    fun `unloaded state is not treated as no locked apps`() {
        // 未ロード中のイベントで間引き状態が「ブロック済み」にならないこと（ロード後の再判定を妨げない）
        val gate = BlockGate(own)
        gate.onWindowEvent("com.sns", now, 0, noExempt)
        gate.onWindowEvent("com.sns", now, 10, noExempt)
        assertEquals("com.sns", gate.onSnapshot(locked, now, 20, noExempt))
    }

    @Test
    fun `own app or home in foreground at load is not blocked`() {
        val gate1 = BlockGate(own)
        gate1.onWindowEvent(own, now, 0, noExempt)
        assertNull(gate1.onSnapshot(locked, now, 10, noExempt))

        val gate2 = BlockGate(own)
        gate2.onWindowEvent("com.sns", now, 0, noExempt)
        gate2.onWindowEvent("com.launcher", now, 5, noExempt) // 最新の前面アプリで判定する
        assertNull(gate2.onSnapshot(locked, now, 10, noExempt))
    }

    @Test
    fun `nothing seen before load means nothing to re-evaluate`() {
        assertNull(BlockGate(own).onSnapshot(locked, now, 0, noExempt))
    }

    @Test
    fun `active grant or exempt package at load is not blocked`() {
        val grant = UnlockGrant(
            day = LocalDate.of(2026, 9, 26), ruleId = 1, completionId = 1,
            grantedAt = now.minusSeconds(60), expiresAt = now.plusSeconds(3600),
        )
        val gate1 = BlockGate(own)
        gate1.onWindowEvent("com.sns", now, 0, noExempt)
        assertNull(gate1.onSnapshot(BlockSnapshot(setOf("com.sns"), grant), now, 10, noExempt))

        val gate2 = BlockGate(own)
        gate2.onWindowEvent("com.sns", now, 0, noExempt)
        assertNull(gate2.onSnapshot(locked, now, 10) { setOf("com.sns") })
    }

    @Test
    fun `only the first load re-evaluates`() {
        val gate = BlockGate(own)
        gate.onWindowEvent("com.sns", now, 0, noExempt)
        assertEquals("com.sns", gate.onSnapshot(locked, now, 10, noExempt))
        assertNull(gate.onSnapshot(locked, now, 2000, noExempt))
    }

    @Test
    fun `after load events block normally including quick re-entry via home`() {
        val gate = BlockGate(own)
        gate.onSnapshot(locked, now, 0, noExempt)
        assertTrue(gate.onWindowEvent("com.sns", now, 100, noExempt))
        assertFalse(gate.onWindowEvent(own, now, 150, noExempt)) // ブロック画面
        assertFalse(gate.onWindowEvent("com.launcher", now, 200, noExempt)) // ホーム
        assertTrue(gate.onWindowEvent("com.sns", now, 300, noExempt)) // 700ms 以内の再侵入もブロック
    }

    @Test
    fun `exempt lookup is skipped for packages that are not locked`() {
        val gate = BlockGate(own)
        gate.onSnapshot(locked, now, 0, noExempt)
        var calls = 0
        gate.onWindowEvent("com.other", now, 10) { calls++; emptySet() }
        assertEquals(0, calls)
    }
}
