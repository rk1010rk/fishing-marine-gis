package jp.tasklock.core

import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.lock.LockEvaluator
import jp.tasklock.core.lock.UnlockDecision
import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.LockRule
import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.VerificationMethod
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.time.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LockEvaluatorTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val boundary = DayBoundary(tokyo)
    private val evaluator = LockEvaluator(boundary)
    private val rule = LockRule(id = 1, type = LockRule.TYPE_DAILY_ANY_ONE_TASK)
    private val day = LocalDate.of(2026, 9, 26)
    private val now = Instant.parse("2026-09-26T03:00:00Z") // 12:00 JST

    private fun task(id: Long, required: VerificationStatus, policy: VerificationPolicy = VerificationPolicy.SELF_REPORT) = Task(
        id = id, templateId = null, title = "t$id", category = TaskCategory.STUDY, unit = TargetUnit.MINUTES,
        targetValue = 30, verificationPolicy = policy, requiredStatus = required, createdAt = now,
    )

    private fun completion(id: Long, taskId: Long, d: LocalDate = day) =
        Completion(id = id, taskId = taskId, day = d, reportedValue = 30, completedAt = now)

    private fun verification(completionId: Long, status: VerificationStatus) =
        Verification(completionId = completionId, status = status, method = VerificationMethod.SELF_REPORT, verifiedAt = now)

    @Test
    fun `no completion keeps locked`() {
        val d = evaluator.evaluate(rule, day, listOf(task(1, VerificationStatus.SELF_REPORTED)), emptyList(), emptyList())
        assertEquals(UnlockDecision.StayLocked(UnlockDecision.Reason.NO_COMPLETION_TODAY), d)
    }

    @Test
    fun `completion without verification does not unlock`() {
        val d = evaluator.evaluate(rule, day, listOf(task(1, VerificationStatus.SELF_REPORTED)), listOf(completion(10, 1)), emptyList())
        assertEquals(UnlockDecision.StayLocked(UnlockDecision.Reason.VERIFICATION_INSUFFICIENT), d)
    }

    @Test
    fun `self report unlocks self report task until next midnight JST`() {
        val d = evaluator.evaluate(
            rule, day, listOf(task(1, VerificationStatus.SELF_REPORTED)),
            listOf(completion(10, 1)), listOf(verification(10, VerificationStatus.SELF_REPORTED)),
        )
        assertEquals(UnlockDecision.Unlock(10, Instant.parse("2026-09-26T15:00:00Z")), d)
    }

    @Test
    fun `partial does not unlock task requiring VERIFIED`() {
        val t = task(1, VerificationStatus.VERIFIED, VerificationPolicy.APP_USAGE)
        val d = evaluator.evaluate(rule, day, listOf(t), listOf(completion(10, 1)), listOf(verification(10, VerificationStatus.PARTIAL)))
        assertTrue(d is UnlockDecision.StayLocked)
    }

    @Test
    fun `best of multiple verifications is used`() {
        val t = task(1, VerificationStatus.VERIFIED, VerificationPolicy.APP_USAGE)
        val d = evaluator.evaluate(
            rule, day, listOf(t), listOf(completion(10, 1)),
            listOf(verification(10, VerificationStatus.PARTIAL), verification(10, VerificationStatus.VERIFIED)),
        )
        assertTrue(d is UnlockDecision.Unlock)
    }

    @Test
    fun `self-report task unlocks even when the verified task could not be measured`() {
        // D2: 資格（VERIFIED、計測不能で UNVERIFIED）＋ 読書（SELF_REPORTED）なら読書で解除できる
        val cert = task(1, VerificationStatus.VERIFIED, VerificationPolicy.APP_USAGE)
        val reading = task(2, VerificationStatus.SELF_REPORTED)
        val d = evaluator.evaluate(
            rule, day, listOf(cert, reading),
            listOf(completion(10, 1), completion(11, 2)),
            listOf(verification(10, VerificationStatus.UNVERIFIED), verification(11, VerificationStatus.SELF_REPORTED)),
        )
        assertEquals(UnlockDecision.Unlock(11, Instant.parse("2026-09-26T15:00:00Z")), d)
    }

    @Test
    fun `verified task is never downgraded to self report`() {
        // VERIFIED 必須タスクに自己申告の検証が付いても解除されない（自動フォールバックしない）
        val cert = task(1, VerificationStatus.VERIFIED, VerificationPolicy.APP_USAGE)
        val d = evaluator.evaluate(
            rule, day, listOf(cert), listOf(completion(10, 1)),
            listOf(verification(10, VerificationStatus.SELF_REPORTED)),
        )
        assertEquals(UnlockDecision.StayLocked(UnlockDecision.Reason.VERIFICATION_INSUFFICIENT), d)
    }

    @Test
    fun `yesterday's completion does not unlock today`() {
        val d = evaluator.evaluate(
            rule, day, listOf(task(1, VerificationStatus.SELF_REPORTED)),
            listOf(completion(10, 1, day.minusDays(1))), listOf(verification(10, VerificationStatus.SELF_REPORTED)),
        )
        assertTrue(d is UnlockDecision.StayLocked)
    }

    @Test
    fun `inactive or unknown rule stays locked`() {
        assertTrue(evaluator.evaluate(null, day, emptyList(), emptyList(), emptyList()) is UnlockDecision.StayLocked)
        val unknown = rule.copy(type = "FUTURE_RULE")
        assertEquals(
            UnlockDecision.StayLocked(UnlockDecision.Reason.UNKNOWN_RULE_TYPE),
            evaluator.evaluate(unknown, day, emptyList(), emptyList(), emptyList()),
        )
    }

    @Test
    fun `unknown status string parses to UNVERIFIED`() {
        assertEquals(VerificationStatus.UNVERIFIED, VerificationStatus.parse("SOMETHING_NEW"))
        assertEquals(VerificationStatus.PHOTO_VERIFIED, VerificationStatus.parse("PHOTO_VERIFIED"))
    }

    @Test
    fun `block snapshot respects grant window`() {
        val grant = UnlockGrant(day = day, ruleId = 1, completionId = 10, grantedAt = now, expiresAt = boundary.endOf(day))
        val locked = BlockSnapshot(setOf("com.sns"), null)
        val unlocked = BlockSnapshot(setOf("com.sns"), grant)
        assertTrue(locked.shouldBlock("com.sns", now))
        assertFalse(locked.shouldBlock("com.other", now))
        assertFalse(unlocked.shouldBlock("com.sns", now))
        assertTrue(unlocked.shouldBlock("com.sns", Instant.parse("2026-09-26T15:00:00Z")))
    }

    @Test
    fun `day boundary with custom start hour`() {
        val b = DayBoundary(tokyo, startHour = 4)
        // 03:00 JST on 9/27 still belongs to 9/26
        assertEquals(day, b.dayOf(Instant.parse("2026-09-26T18:00:00Z")))
        assertEquals(Instant.parse("2026-09-26T19:00:00Z"), b.endOf(day))
    }
}
