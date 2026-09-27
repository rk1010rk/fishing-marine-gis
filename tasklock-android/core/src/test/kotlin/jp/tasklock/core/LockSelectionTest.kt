package jp.tasklock.core

import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.policy.ChangeRejection
import jp.tasklock.core.policy.ChangeResult
import jp.tasklock.core.policy.LockNotice
import jp.tasklock.core.policy.LockSelection
import jp.tasklock.core.policy.LockSelectionDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** DESIGN.md §9.6-6: ロック対象の選択を「確定」で反映する */
class LockSelectionTest {
    private val now = Instant.parse("2026-09-27T03:00:00Z")
    private val sms = "com.google.android.apps.messaging"

    private val grant = UnlockGrant(
        day = LocalDate.of(2026, 9, 27), ruleId = 1, completionId = 1,
        grantedAt = Instant.parse("2026-09-27T01:00:00Z"), expiresAt = Instant.parse("2026-09-27T15:00:00Z"),
    )

    private fun studyTask(pkg: String) = Task(
        id = 1, templateId = "cert_app_minutes", title = "資格", category = TaskCategory.CERTIFICATION,
        unit = TargetUnit.MINUTES, targetValue = 30, verificationPolicy = VerificationPolicy.APP_USAGE,
        requiredStatus = VerificationStatus.VERIFIED, targetPackage = pkg, createdAt = now,
    )

    // ---- 差分と確認画面の要否 ----

    @Test
    fun `adding an app needs confirmation`() {
        val diff = LockSelection.diff(saved = setOf("youtube"), draft = setOf("youtube", "x"))
        assertEquals(LockSelectionDiff(added = setOf("x"), removed = emptySet()), diff)
        assertTrue(diff.needsConfirmation)
    }

    @Test
    fun `removal only does not need confirmation`() {
        val diff = LockSelection.diff(saved = setOf("youtube", "x"), draft = setOf("youtube"))
        assertEquals(LockSelectionDiff(added = emptySet(), removed = setOf("x")), diff)
        assertFalse(diff.needsConfirmation)
    }

    @Test
    fun `adding and removing together needs confirmation`() {
        val diff = LockSelection.diff(saved = setOf("youtube"), draft = setOf("x"))
        assertEquals(LockSelectionDiff(added = setOf("x"), removed = setOf("youtube")), diff)
        assertTrue(diff.needsConfirmation)
    }

    @Test
    fun `no change is empty and needs no confirmation`() {
        val diff = LockSelection.diff(saved = setOf("youtube"), draft = setOf("youtube"))
        assertTrue(diff.isEmpty)
        assertFalse(diff.needsConfirmation)
    }

    // ---- まとめて反映してよいか（反映前の状態で判定） ----

    @Test
    fun `adding while locked is allowed`() {
        val diff = LockSelectionDiff(added = setOf("x"), removed = emptySet())
        assertEquals(ChangeResult.Ok, LockSelection.canApply(diff, lockedBefore = true, emptyList(), emptySet()))
    }

    @Test
    fun `removing while locked is rejected`() {
        val diff = LockSelectionDiff(added = emptySet(), removed = setOf("youtube"))
        assertEquals(
            ChangeResult.Rejected(ChangeRejection.LOCKED),
            LockSelection.canApply(diff, lockedBefore = true, emptyList(), emptySet()),
        )
    }

    @Test
    fun `removing while unlocked is allowed`() {
        val diff = LockSelectionDiff(added = emptySet(), removed = setOf("youtube"))
        assertEquals(ChangeResult.Ok, LockSelection.canApply(diff, lockedBefore = false, emptyList(), emptySet()))
    }

    @Test
    fun `whole change is rejected when any removal is rejected even if additions are fine`() {
        val diff = LockSelectionDiff(added = setOf("x"), removed = setOf("youtube"))
        assertEquals(
            ChangeResult.Rejected(ChangeRejection.LOCKED),
            LockSelection.canApply(diff, lockedBefore = true, emptyList(), emptySet()),
        )
    }

    @Test
    fun `whole change is rejected when any addition is an exempt app such as the default SMS app`() {
        val diff = LockSelectionDiff(added = setOf("x", sms), removed = emptySet())
        assertEquals(
            ChangeResult.Rejected(ChangeRejection.APP_IS_EXEMPT),
            LockSelection.canApply(diff, lockedBefore = false, emptyList(), exemptPackages = setOf(sms)),
        )
    }

    @Test
    fun `whole change is rejected when any addition is a study app`() {
        val diff = LockSelectionDiff(added = setOf("x", "study.app"), removed = emptySet())
        assertEquals(
            ChangeResult.Rejected(ChangeRejection.APP_IS_STUDY_TARGET),
            LockSelection.canApply(diff, lockedBefore = false, listOf(studyTask("study.app")), emptySet()),
        )
    }

    // ---- 確認画面の「ロックに関する注意」 ----

    @Test
    fun `adding while locked warns that the apps cannot be removed today`() {
        val diff = LockSelectionDiff(added = setOf("x"), removed = emptySet())
        assertEquals(LockNotice.ADDING_WHILE_LOCKED, LockSelection.notice(setOf("youtube"), diff, grant = null, now))
    }

    @Test
    fun `first addition without a grant starts the lock`() {
        val diff = LockSelectionDiff(added = setOf("youtube"), removed = emptySet())
        assertEquals(LockNotice.STARTS_LOCK, LockSelection.notice(emptySet(), diff, grant = null, now))
    }

    @Test
    fun `adding while unlocked for today does not start the lock`() {
        val diff = LockSelectionDiff(added = setOf("x"), removed = emptySet())
        assertEquals(LockNotice.NONE, LockSelection.notice(setOf("youtube"), diff, grant, now))
    }

    @Test
    fun `removal only has no lock notice`() {
        val diff = LockSelectionDiff(added = emptySet(), removed = setOf("youtube"))
        assertEquals(LockNotice.NONE, LockSelection.notice(setOf("youtube"), diff, grant, now))
    }
}
