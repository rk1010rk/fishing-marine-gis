package jp.tasklock.core.model

import java.time.Instant
import java.time.LocalDate

enum class TaskCategory { STUDY, CERTIFICATION, READING, EXERCISE, CUSTOM }

enum class TargetUnit(val label: String) {
    PAGES("ページ"),
    PROBLEMS("問"),
    MINUTES("分"),
    STEPS("歩"),
    REPS("回"),
    NONE(""),
}

/**
 * タスクの達成をどう確認するか。
 * Phase 1 は SELF_REPORT と APP_USAGE のみ。HEALTH_CONNECT_STEPS / PHOTO は将来追加。
 */
enum class VerificationPolicy {
    /** 自己申告（読書はページ整合性チェック付き） */
    SELF_REPORT,

    /** UsageStatsManager で対象アプリの前面表示時間を計測 */
    APP_USAGE,
}

data class Task(
    val id: Long = 0,
    val templateId: String?,
    val title: String,
    val category: TaskCategory,
    val unit: TargetUnit,
    val targetValue: Int,
    val verificationPolicy: VerificationPolicy,
    /** ロック解除に必要な最低検証レベル */
    val requiredStatus: VerificationStatus,
    /** APP_USAGE の計測対象パッケージ */
    val targetPackage: String? = null,
    val active: Boolean = true,
    val createdAt: Instant,
)

/**
 * 「完了した」という申告。信頼の根拠は持たず、検証は [Verification] 側に分離する。
 */
data class Completion(
    val id: Long = 0,
    val taskId: Long,
    /** どの「日」の完了として扱うか（日付境界は DayBoundary で決める） */
    val day: LocalDate,
    val reportedValue: Int,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val note: String? = null,
    val completedAt: Instant,
)

/**
 * ロック条件。MVPは [TYPE_DAILY_ANY_ONE_TASK] のみだが、type + params で拡張できる形にしておく。
 */
data class LockRule(
    val id: Long = 0,
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val active: Boolean = true,
) {
    companion object {
        const val TYPE_DAILY_ANY_ONE_TASK = "DAILY_ANY_ONE_TASK"
    }
}

/** ロック解除の記録。どの Completion によって、いつまで解除されたか。 */
data class UnlockGrant(
    val id: Long = 0,
    val day: LocalDate,
    val ruleId: Long,
    val completionId: Long,
    val grantedAt: Instant,
    val expiresAt: Instant,
) {
    fun isActiveAt(now: Instant): Boolean = !now.isBefore(grantedAt) && now.isBefore(expiresAt)
}
