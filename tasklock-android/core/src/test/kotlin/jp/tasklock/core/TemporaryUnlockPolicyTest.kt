package jp.tasklock.core

import jp.tasklock.core.model.TemporaryUnlock
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.policy.TemporaryUnlockDecision
import jp.tasklock.core.policy.TemporaryUnlockPolicy
import jp.tasklock.core.policy.TemporaryUnlockRejection
import jp.tasklock.core.time.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TemporaryUnlockPolicyTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val boundary = DayBoundary(tokyo)
    private val now = Instant.parse("2026-10-05T03:00:00Z") // 12:00 JST
    private val day = LocalDate.of(2026, 10, 5)
    private val apps = setOf("com.sns")

    private val activeGrant = UnlockGrant(
        day = day, ruleId = 1, completionId = 1,
        grantedAt = now.minusSeconds(3600), expiresAt = now.plusSeconds(3600),
    )
    private val expiredGrant = activeGrant.copy(
        day = day.minusDays(1), grantedAt = now.minus(Duration.ofDays(1)), expiresAt = now.minusSeconds(3600),
    )
    private val activeTemporary = TemporaryUnlock(day = day, startedAt = now.minusSeconds(60), expiresAt = now.plusSeconds(540))
    private val expiredTemporary =
        TemporaryUnlock(day = day, startedAt = now.minusSeconds(3600), expiresAt = now.minusSeconds(3000))

    private fun decide(
        at: Instant = now,
        lockedPackages: Set<String> = apps,
        grant: UnlockGrant? = null,
        latest: TemporaryUnlock? = null,
        startedToday: Int = 0,
    ) = TemporaryUnlockPolicy.decide(at, boundary, lockedPackages, grant, latest, startedToday)

    private fun rejected(reason: TemporaryUnlockRejection) = TemporaryUnlockDecision.Rejected(reason)

    @Test
    fun `constants are 10 minutes and 2 per day`() {
        assertEquals(Duration.ofMinutes(10), TemporaryUnlockPolicy.DURATION)
        assertEquals(2, TemporaryUnlockPolicy.DAILY_LIMIT)
    }

    @Test
    fun `allowed unlock starts now on today and lasts 10 minutes`() {
        val decision = decide()
        assertTrue(decision is TemporaryUnlockDecision.Allowed)
        val unlock = (decision as TemporaryUnlockDecision.Allowed).unlock
        assertEquals(0L, unlock.id)
        assertEquals(day, unlock.day)
        assertEquals(now, unlock.startedAt)
        assertEquals(now.plus(Duration.ofMinutes(10)), unlock.expiresAt)
    }

    @Test
    fun `each rejection reason`() {
        assertEquals(rejected(TemporaryUnlockRejection.NOT_LOCKED), decide(lockedPackages = emptySet()))
        assertEquals(rejected(TemporaryUnlockRejection.TASK_UNLOCKED), decide(grant = activeGrant))
        assertEquals(rejected(TemporaryUnlockRejection.ALREADY_ACTIVE), decide(latest = activeTemporary, startedToday = 1))
        assertEquals(rejected(TemporaryUnlockRejection.DAILY_LIMIT_REACHED), decide(startedToday = 2))
    }

    @Test
    fun `rejection priority when several conditions hold`() {
        assertEquals(
            rejected(TemporaryUnlockRejection.NOT_LOCKED),
            decide(lockedPackages = emptySet(), grant = activeGrant, latest = activeTemporary, startedToday = 2),
        )
        assertEquals(
            rejected(TemporaryUnlockRejection.TASK_UNLOCKED),
            decide(grant = activeGrant, latest = activeTemporary, startedToday = 2),
        )
        assertEquals(
            rejected(TemporaryUnlockRejection.ALREADY_ACTIVE),
            decide(latest = activeTemporary, startedToday = 2),
        )
    }

    @Test
    fun `daily count 0 and 1 are allowed and 2 or more is rejected`() {
        assertTrue(decide(startedToday = 0) is TemporaryUnlockDecision.Allowed)
        assertTrue(decide(startedToday = 1, latest = expiredTemporary) is TemporaryUnlockDecision.Allowed)
        assertEquals(rejected(TemporaryUnlockRejection.DAILY_LIMIT_REACHED), decide(startedToday = 2, latest = expiredTemporary))
        assertEquals(rejected(TemporaryUnlockRejection.DAILY_LIMIT_REACHED), decide(startedToday = 3))
    }

    @Test
    fun `expired task unlock does not prevent starting`() {
        assertTrue(decide(grant = expiredGrant) is TemporaryUnlockDecision.Allowed)
    }

    @Test
    fun `unlock started before midnight stays active after midnight and counts on its start day`() {
        val started = Instant.parse("2026-10-05T14:55:00Z") // 23:55 JST
        val first = (decide(at = started) as TemporaryUnlockDecision.Allowed).unlock
        assertEquals(LocalDate.of(2026, 10, 5), first.day)
        assertEquals(Instant.parse("2026-10-05T15:05:00Z"), first.expiresAt) // 0:05 JST

        val afterMidnight = Instant.parse("2026-10-05T15:03:00Z") // 0:03 JST（翌日）
        // 新しい日の件数は 0 だが、前日に始めた一時解除がまだ有効なので開始できない
        assertEquals(rejected(TemporaryUnlockRejection.ALREADY_ACTIVE), decide(at = afterMidnight, latest = first, startedToday = 0))

        // 期限後は新しい日の回数で判定し、新しい記録は新しい日に数える
        val later = Instant.parse("2026-10-05T15:06:00Z") // 0:06 JST
        val next = (decide(at = later, latest = first, startedToday = 0) as TemporaryUnlockDecision.Allowed).unlock
        assertEquals(LocalDate.of(2026, 10, 6), next.day)
    }

    @Test
    fun `temporary unlock requires expiresAt after startedAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            TemporaryUnlock(day = day, startedAt = now, expiresAt = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemporaryUnlock(day = day, startedAt = now, expiresAt = now.minusSeconds(1))
        }
    }

    @Test
    fun `remaining today`() {
        assertEquals(2, TemporaryUnlockPolicy.remainingToday(0))
        assertEquals(1, TemporaryUnlockPolicy.remainingToday(1))
        assertEquals(0, TemporaryUnlockPolicy.remainingToday(2))
        assertEquals(0, TemporaryUnlockPolicy.remainingToday(5))
    }

    @Test
    fun `month range covers the calendar month including end of February`() {
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), TemporaryUnlockPolicy.monthRange(day))
        assertEquals(
            LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28),
            TemporaryUnlockPolicy.monthRange(LocalDate.of(2026, 2, 14)),
        )
        assertEquals(
            LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29), // うるう年
            TemporaryUnlockPolicy.monthRange(LocalDate.of(2028, 2, 29)),
        )
        assertEquals(
            LocalDate.of(2026, 12, 1) to LocalDate.of(2026, 12, 31),
            TemporaryUnlockPolicy.monthRange(LocalDate.of(2026, 12, 1)),
        )
    }
}
