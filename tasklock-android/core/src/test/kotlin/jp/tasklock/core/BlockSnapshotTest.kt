package jp.tasklock.core

import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.model.TemporaryUnlock
import jp.tasklock.core.model.UnlockGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** ブロックするか（shouldBlock）とロック中か（isLockedAt）の分離（DESIGN.md §9.6-2「一時解除と緊急解除」） */
class BlockSnapshotTest {
    private val now = Instant.parse("2026-10-05T03:00:00Z")
    private val day = LocalDate.of(2026, 10, 5)
    private val apps = setOf("com.sns")

    private val grant = UnlockGrant(
        day = day, ruleId = 1, completionId = 1,
        grantedAt = now.minusSeconds(3600), expiresAt = now.plusSeconds(3600),
    )
    private val temporary = TemporaryUnlock(day = day, startedAt = now.minusSeconds(60), expiresAt = now.plusSeconds(540))
    private val expiredTemporary =
        TemporaryUnlock(day = day, startedAt = now.minusSeconds(900), expiresAt = now.minusSeconds(300))

    /** (shouldBlock, isLockedAt) */
    private fun BlockSnapshot.state(pkg: String = "com.sns", at: Instant = now) = shouldBlock(pkg, at) to isLockedAt(at)

    @Test
    fun `normal lock blocks and is locked`() {
        assertEquals(true to true, BlockSnapshot(apps, null).state())
    }

    @Test
    fun `task unlock neither blocks nor is locked`() {
        assertEquals(false to false, BlockSnapshot(apps, grant).state())
    }

    @Test
    fun `temporary unlock does not block but stays locked`() {
        assertEquals(false to true, BlockSnapshot(apps, null, temporary).state())
    }

    @Test
    fun `expired temporary unlock blocks again and is locked`() {
        assertEquals(true to true, BlockSnapshot(apps, null, expiredTemporary).state())
    }

    @Test
    fun `no locked apps neither blocks nor is locked`() {
        assertEquals(false to false, BlockSnapshot(emptySet(), null).state())
        assertEquals(false to false, BlockSnapshot(emptySet(), null, temporary).state())
    }

    @Test
    fun `temporary unlock and task unlock both active`() {
        assertEquals(false to false, BlockSnapshot(apps, grant, temporary).state())
    }

    @Test
    fun `temporary unlock is active from startedAt and inactive at expiresAt`() {
        val snap = BlockSnapshot(apps, null, temporary)
        assertEquals(false to true, snap.state(at = temporary.startedAt))
        assertEquals(true to true, snap.state(at = temporary.startedAt.minusMillis(1)))
        assertEquals(true to true, snap.state(at = temporary.expiresAt))
        assertEquals(false to true, snap.state(at = temporary.expiresAt.minusMillis(1)))
    }

    @Test
    fun `temporary unlock applies to all locked apps including ones added later`() {
        // 一時解除の記録はロック対象の集合に依存しない
        val snap = BlockSnapshot(setOf("com.sns", "com.game", "com.added.later"), null, temporary)
        assertFalse(snap.shouldBlock("com.sns", now))
        assertFalse(snap.shouldBlock("com.game", now))
        assertFalse(snap.shouldBlock("com.added.later", now))
        assertTrue(snap.isLockedAt(now))
    }

    @Test
    fun `exempt packages are never blocked in any state`() {
        val exempt = setOf("com.sns")
        listOf(
            BlockSnapshot(apps, null),
            BlockSnapshot(apps, grant),
            BlockSnapshot(apps, null, temporary),
            BlockSnapshot(apps, null, expiredTemporary),
        ).forEach { assertFalse(it.shouldBlock("com.sns", now, exempt)) }
    }

    @Test
    fun `unlisted package is not blocked`() {
        assertFalse(BlockSnapshot(apps, null).shouldBlock("com.other", now))
    }
}
