package jp.tasklock.core

import jp.tasklock.core.lock.BlockDebouncer
import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.policy.ChangePolicy
import jp.tasklock.core.policy.ChangeRejection
import jp.tasklock.core.policy.ChangeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ChangePolicyTest {
    private val now = Instant.parse("2026-09-26T03:00:00Z")

    private fun selfTask(id: Long) = Task(
        id = id, templateId = "reading_pages", title = "読書", category = TaskCategory.READING, unit = TargetUnit.PAGES,
        targetValue = 20, verificationPolicy = VerificationPolicy.SELF_REPORT,
        requiredStatus = VerificationStatus.SELF_REPORTED, createdAt = now,
    )

    private fun verifiedTask(id: Long, pkg: String = "study.app") = Task(
        id = id, templateId = "cert_app_minutes", title = "資格", category = TaskCategory.CERTIFICATION,
        unit = TargetUnit.MINUTES, targetValue = 30, verificationPolicy = VerificationPolicy.APP_USAGE,
        requiredStatus = VerificationStatus.VERIFIED, targetPackage = pkg, createdAt = now,
    )

    private fun rejected(reason: ChangeRejection) = ChangeResult.Rejected(reason)

    // ---- 1. ロック中のタスク変更（方式B） ----

    @Test
    fun `adding a task while locked is rejected when tasks exist`() {
        assertEquals(
            rejected(ChangeRejection.LOCKED),
            ChangePolicy.canAddTask(locked = true, listOf(selfTask(1)), selfTask(0), emptySet()),
        )
    }

    @Test
    fun `adding the first task while locked is allowed`() {
        assertEquals(ChangeResult.Ok, ChangePolicy.canAddTask(locked = true, emptyList(), selfTask(0), emptySet()))
    }

    @Test
    fun `adding a task while unlocked is allowed`() {
        assertEquals(ChangeResult.Ok, ChangePolicy.canAddTask(locked = false, listOf(selfTask(1)), selfTask(0), emptySet()))
    }

    @Test
    fun `removing a task while locked is rejected`() {
        assertEquals(
            rejected(ChangeRejection.LOCKED),
            ChangePolicy.canRemoveTask(locked = true, listOf(selfTask(1), selfTask(2)), 1),
        )
    }

    // ---- 2. D2: 自己申告で解除できるタスクを最低1つ ----

    @Test
    fun `verified-only task set is rejected`() {
        assertEquals(
            rejected(ChangeRejection.NEEDS_SELF_REPORTED_TASK),
            ChangePolicy.canAddTask(locked = false, emptyList(), verifiedTask(0), emptySet()),
        )
    }

    @Test
    fun `verified task alongside a self-reported task is allowed`() {
        assertEquals(ChangeResult.Ok, ChangePolicy.canAddTask(locked = false, listOf(selfTask(1)), verifiedTask(0), emptySet()))
    }

    @Test
    fun `removing the only self-reported task next to a verified one is rejected`() {
        assertEquals(
            rejected(ChangeRejection.NEEDS_SELF_REPORTED_TASK),
            ChangePolicy.canRemoveTask(locked = false, listOf(selfTask(1), verifiedTask(2)), 1),
        )
    }

    @Test
    fun `removing the verified task or the last task is allowed`() {
        assertEquals(ChangeResult.Ok, ChangePolicy.canRemoveTask(locked = false, listOf(selfTask(1), verifiedTask(2)), 2))
        assertEquals(ChangeResult.Ok, ChangePolicy.canRemoveTask(locked = false, listOf(selfTask(1)), 1))
    }

    @Test
    fun `verified task keeps its requirement - it is not self reportable`() {
        assertFalse(ChangePolicy.isSelfReportable(verifiedTask(1)))
        assertTrue(ChangePolicy.isSelfReportable(selfTask(1)))
    }

    // ---- 3. D1: 学習アプリ ∩ ロック対象 = ∅ ----

    @Test
    fun `study app cannot be a locked app`() {
        assertEquals(
            rejected(ChangeRejection.STUDY_APP_IS_LOCKED),
            ChangePolicy.canAddTask(locked = false, listOf(selfTask(1)), verifiedTask(0, "com.sns"), setOf("com.sns")),
        )
    }

    @Test
    fun `locked app cannot be a study app`() {
        assertEquals(
            rejected(ChangeRejection.APP_IS_STUDY_TARGET),
            ChangePolicy.canLockApp("study.app", listOf(selfTask(1), verifiedTask(2, "study.app")), emptySet()),
        )
        assertEquals(ChangeResult.Ok, ChangePolicy.canLockApp("com.sns", listOf(verifiedTask(2, "study.app")), emptySet()))
    }

    @Test
    fun `exempt apps cannot be locked and unlocking apps requires unlocked state`() {
        assertEquals(rejected(ChangeRejection.APP_IS_EXEMPT), ChangePolicy.canLockApp("launcher", emptyList(), setOf("launcher")))
        assertEquals(rejected(ChangeRejection.LOCKED), ChangePolicy.canUnlockApp(locked = true))
        assertEquals(ChangeResult.Ok, ChangePolicy.canUnlockApp(locked = false))
    }

    // ---- ロック判定 ----

    @Test
    fun `lock state derives from locked apps and grant`() {
        val grant = UnlockGrant(
            day = LocalDate.of(2026, 9, 26), ruleId = 1, completionId = 1,
            grantedAt = now, expiresAt = Instant.parse("2026-09-26T15:00:00Z"),
        )
        assertFalse(BlockSnapshot(emptySet(), null).isLockedAt(now))
        assertTrue(BlockSnapshot(setOf("com.sns"), null).isLockedAt(now))
        assertFalse(BlockSnapshot(setOf("com.sns"), grant).isLockedAt(now))
        assertTrue(BlockSnapshot(setOf("com.sns"), grant).isLockedAt(Instant.parse("2026-09-26T15:00:00Z")))
    }

    @Test
    fun `exempt package is never blocked at runtime`() {
        val snap = BlockSnapshot(setOf("com.sns", "com.dialer"), null)
        assertTrue(snap.shouldBlock("com.sns", now, setOf("com.dialer")))
        assertFalse(snap.shouldBlock("com.dialer", now, setOf("com.dialer")))
    }

    // ---- 700ms 間引き ----

    @Test
    fun `burst of events from the same app launches block screen once`() {
        val d = BlockDebouncer(700)
        assertTrue(d.onWindowEvent("com.sns", true, 0))
        assertFalse(d.onWindowEvent("com.sns", true, 100))
        assertFalse(d.onWindowEvent("com.sns", true, 600))
    }

    @Test
    fun `sns - block - home - sns within 700ms is blocked again`() {
        val d = BlockDebouncer(700)
        assertTrue(d.onWindowEvent("com.sns", true, 0))
        assertFalse(d.onWindowEvent("jp.tasklock.app", false, 50)) // ブロック画面
        assertFalse(d.onWindowEvent("com.launcher", false, 150)) // ホーム
        assertTrue(d.onWindowEvent("com.sns", true, 300)) // すぐ再起動
    }

    @Test
    fun `switching directly from block screen back to the app is blocked`() {
        val d = BlockDebouncer(700)
        assertTrue(d.onWindowEvent("com.sns", true, 0))
        assertFalse(d.onWindowEvent("jp.tasklock.app", false, 50))
        assertTrue(d.onWindowEvent("com.sns", true, 200))
    }

    @Test
    fun `same app after the window elapses is blocked again`() {
        val d = BlockDebouncer(700)
        assertTrue(d.onWindowEvent("com.sns", true, 0))
        assertTrue(d.onWindowEvent("com.sns", true, 800))
    }
}
