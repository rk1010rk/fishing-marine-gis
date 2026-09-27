package jp.tasklock.core.lock

import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.LockRule
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.bestStatus
import jp.tasklock.core.time.DayBoundary
import java.time.Instant
import java.time.LocalDate

sealed interface UnlockDecision {
    data class Unlock(val completionId: Long, val expiresAt: Instant) : UnlockDecision
    data class StayLocked(val reason: Reason) : UnlockDecision

    enum class Reason { NO_ACTIVE_RULE, NO_COMPLETION_TODAY, VERIFICATION_INSUFFICIENT, UNKNOWN_RULE_TYPE }
}

/**
 * Task → Completion → Verification → Unlock の最後の段。
 * Completion の有無ではなく「その Completion に付いた検証が Task の要求レベルを満たすか」で判定する。
 */
class LockEvaluator(private val boundary: DayBoundary) {

    fun evaluate(
        rule: LockRule?,
        day: LocalDate,
        tasks: List<Task>,
        completions: List<Completion>,
        verifications: List<Verification>,
    ): UnlockDecision {
        if (rule == null || !rule.active) return UnlockDecision.StayLocked(UnlockDecision.Reason.NO_ACTIVE_RULE)
        return when (rule.type) {
            LockRule.TYPE_DAILY_ANY_ONE_TASK -> evaluateDailyAnyOne(day, tasks, completions, verifications)
            else -> UnlockDecision.StayLocked(UnlockDecision.Reason.UNKNOWN_RULE_TYPE)
        }
    }

    private fun evaluateDailyAnyOne(
        day: LocalDate,
        tasks: List<Task>,
        completions: List<Completion>,
        verifications: List<Verification>,
    ): UnlockDecision {
        val tasksById = tasks.associateBy { it.id }
        val todays = completions.filter { it.day == day && it.taskId in tasksById }
        if (todays.isEmpty()) return UnlockDecision.StayLocked(UnlockDecision.Reason.NO_COMPLETION_TODAY)

        val byCompletion = verifications.groupBy { it.completionId }
        val satisfied = todays
            .sortedBy { it.completedAt }
            .firstOrNull { c ->
                val task = tasksById.getValue(c.taskId)
                byCompletion[c.id].orEmpty().bestStatus().satisfies(task.requiredStatus)
            }
            ?: return UnlockDecision.StayLocked(UnlockDecision.Reason.VERIFICATION_INSUFFICIENT)

        return UnlockDecision.Unlock(satisfied.id, boundary.endOf(day))
    }
}

/**
 * アクセシビリティサービスから高頻度で参照される判定。I/O を含まない純粋関数にしている。
 */
data class BlockSnapshot(
    val lockedPackages: Set<String>,
    val grant: UnlockGrant?,
) {
    /**
     * @param exemptPackages 実行時点のホーム・電話・自アプリ等。ロック対象に登録済みでも決してブロックしない
     *   （登録後に既定のホーム/電話アプリが変わった場合への備え）
     */
    fun shouldBlock(packageName: String, now: Instant, exemptPackages: Set<String> = emptySet()): Boolean {
        if (packageName !in lockedPackages) return false
        if (packageName in exemptPackages) return false
        return !isUnlockedAt(now)
    }

    /** ロック対象があり、かつ有効な解除記録が無ければロック中 */
    fun isLockedAt(now: Instant): Boolean = lockedPackages.isNotEmpty() && !isUnlockedAt(now)

    private fun isUnlockedAt(now: Instant): Boolean = grant?.isActiveAt(now) == true
}
