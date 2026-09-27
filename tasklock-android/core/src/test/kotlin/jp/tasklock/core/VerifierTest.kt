package jp.tasklock.core

import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.template.TaskTemplates
import jp.tasklock.core.verify.ReadingCheck
import jp.tasklock.core.verify.UsageEvent
import jp.tasklock.core.verify.UsageEvent.Kind.BACKGROUND
import jp.tasklock.core.verify.UsageEvent.Kind.END_ALL
import jp.tasklock.core.verify.UsageEvent.Kind.FOREGROUND
import jp.tasklock.core.verify.UsageTimeCalculator
import jp.tasklock.core.verify.Verifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class VerifierTest {
    private val start = Instant.parse("2026-09-25T15:00:00Z")
    private val end = Instant.parse("2026-09-26T15:00:00Z")
    private fun at(minutes: Long) = start.plus(Duration.ofMinutes(minutes))
    private fun ev(min: Long, pkg: String?, kind: UsageEvent.Kind) = UsageEvent(at(min), pkg, kind)

    @Test
    fun `sums simple foreground sessions`() {
        val events = listOf(
            ev(10, "study", FOREGROUND), ev(25, "study", BACKGROUND),
            ev(30, "sns", FOREGROUND), ev(40, "sns", BACKGROUND),
            ev(60, "study", FOREGROUND), ev(80, "study", BACKGROUND),
        )
        assertEquals(Duration.ofMinutes(35), UsageTimeCalculator.foregroundTime(events, "study", start, end))
    }

    @Test
    fun `in-app activity switch with resume before pause is continuous`() {
        val events = listOf(ev(0, "study", FOREGROUND), ev(10, "study", FOREGROUND), ev(10, "study", BACKGROUND), ev(20, "study", BACKGROUND))
        assertEquals(Duration.ofMinutes(20), UsageTimeCalculator.foregroundTime(events, "study", start, end))
    }

    @Test
    fun `screen off ends session and still-open session counts to window end`() {
        val events = listOf(ev(0, "study", FOREGROUND), ev(15, null, END_ALL), ev(100, "study", FOREGROUND))
        val windowEnd = at(110)
        assertEquals(Duration.ofMinutes(25), UsageTimeCalculator.foregroundTime(events, "study", start, windowEnd))
    }

    @Test
    fun `session started before window counts from window start`() {
        val events = listOf(ev(-30, "study", FOREGROUND), ev(12, "study", BACKGROUND))
        assertEquals(Duration.ofMinutes(12), UsageTimeCalculator.foregroundTime(events, "study", start, end))
    }

    @Test
    fun `app usage verification levels`() {
        val task = Task(
            id = 1, templateId = null, title = "t", category = TaskCategory.CERTIFICATION, unit = TargetUnit.MINUTES,
            targetValue = 30, verificationPolicy = VerificationPolicy.APP_USAGE, requiredStatus = VerificationStatus.VERIFIED,
            targetPackage = "study", createdAt = start,
        )
        val c = Completion(id = 5, taskId = 1, day = LocalDate.of(2026, 9, 26), reportedValue = 30, completedAt = end)
        assertEquals(VerificationStatus.VERIFIED, Verifier.appUsage(task, c, Duration.ofMinutes(31), end).status)
        assertEquals(VerificationStatus.PARTIAL, Verifier.appUsage(task, c, Duration.ofMinutes(12), end).status)
        assertEquals(VerificationStatus.UNVERIFIED, Verifier.appUsage(task, c, Duration.ofSeconds(40), end).status)
    }

    @Test
    fun `reading consistency`() {
        assertEquals(ReadingCheck.Ok, Verifier.checkReading(null, 1, 20, 20))
        assertEquals(ReadingCheck.Ok, Verifier.checkReading(20, 21, 40, 20))
        assertEquals(ReadingCheck.Ok, Verifier.checkReading(40, 35, 54, 20)) // 読み返しは許容
        assertTrue(Verifier.checkReading(20, 50, 69, 20) is ReadingCheck.Mismatch) // 飛ばし
        assertTrue(Verifier.checkReading(null, 1, 10, 20) is ReadingCheck.Mismatch) // ページ数不一致
        assertTrue(Verifier.checkReading(null, 30, 10, 20) is ReadingCheck.Mismatch)
    }

    @Test
    fun `mvp templates are the four required categories`() {
        assertEquals(
            listOf(TaskCategory.STUDY, TaskCategory.CERTIFICATION, TaskCategory.READING, TaskCategory.EXERCISE),
            TaskTemplates.MVP.map { it.category },
        )
    }
}
