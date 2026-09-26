package jp.tasklock.core.template

import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus

data class TaskTemplate(
    val id: String,
    val category: TaskCategory,
    val title: String,
    val description: String,
    val unit: TargetUnit,
    val defaultTarget: Int,
    val policy: VerificationPolicy,
    val requiredStatus: VerificationStatus,
    /** MVPのUIに出すか。自由入力はデータとしては持つがMVPでは非表示 */
    val shownInMvp: Boolean = true,
)

object TaskTemplates {
    val ALL: List<TaskTemplate> = listOf(
        TaskTemplate(
            id = "study_problems",
            category = TaskCategory.STUDY,
            title = "問題集を解く",
            description = "受験勉強。問題集・参考書の問題を指定数解く",
            unit = TargetUnit.PROBLEMS,
            defaultTarget = 10,
            policy = VerificationPolicy.SELF_REPORT,
            requiredStatus = VerificationStatus.SELF_REPORTED,
        ),
        TaskTemplate(
            id = "cert_app_minutes",
            category = TaskCategory.CERTIFICATION,
            title = "資格の学習アプリで勉強",
            description = "指定した学習アプリを目標時間以上使うと自動で確認されます",
            unit = TargetUnit.MINUTES,
            defaultTarget = 30,
            policy = VerificationPolicy.APP_USAGE,
            requiredStatus = VerificationStatus.VERIFIED,
        ),
        TaskTemplate(
            id = "reading_pages",
            category = TaskCategory.READING,
            title = "本を読む",
            description = "読んだページ範囲を記録します",
            unit = TargetUnit.PAGES,
            defaultTarget = 20,
            policy = VerificationPolicy.SELF_REPORT,
            requiredStatus = VerificationStatus.SELF_REPORTED,
        ),
        TaskTemplate(
            id = "exercise_reps",
            category = TaskCategory.EXERCISE,
            title = "筋トレ・運動",
            description = "腕立て・スクワット等の回数を記録します（歩数連携は今後対応）",
            unit = TargetUnit.REPS,
            defaultTarget = 30,
            policy = VerificationPolicy.SELF_REPORT,
            requiredStatus = VerificationStatus.SELF_REPORTED,
        ),
        TaskTemplate(
            id = "custom",
            category = TaskCategory.CUSTOM,
            title = "自由入力",
            description = "自分で決めたタスク",
            unit = TargetUnit.NONE,
            defaultTarget = 1,
            policy = VerificationPolicy.SELF_REPORT,
            requiredStatus = VerificationStatus.SELF_REPORTED,
            shownInMvp = false,
        ),
    )

    val MVP: List<TaskTemplate> = ALL.filter { it.shownInMvp }

    fun byId(id: String?): TaskTemplate? = ALL.firstOrNull { it.id == id }
}
