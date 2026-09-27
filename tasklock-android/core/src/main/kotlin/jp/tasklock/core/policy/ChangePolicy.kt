package jp.tasklock.core.policy

import jp.tasklock.core.model.Task
import jp.tasklock.core.model.VerificationStatus

/** 設定変更を拒否する理由。UI にはそのまま [message] を表示する */
enum class ChangeRejection(val message: String) {
    LOCKED("ロック中は変更できません。今日のタスクを終えてから変更してください"),
    NEEDS_SELF_REPORTED_TASK(
        "自動で確認するタスクだけでは、計測できなくなったときに解除できなくなります。先に「完了を記録する」タイプのタスクを1つ設定してください",
    ),
    STUDY_APP_IS_LOCKED("ロック対象のアプリは学習アプリに設定できません（開けないため計測できなくなります）"),
    APP_IS_STUDY_TARGET("学習アプリに設定しているアプリはロックできません"),
    APP_IS_EXEMPT("ホーム・電話・設定アプリはロックできません"),
}

sealed interface ChangeResult {
    data object Ok : ChangeResult
    data class Rejected(val reason: ChangeRejection) : ChangeResult
}

/**
 * 解除条件・ロック対象を変える操作の可否。I/O を持たない純粋関数で、Repository が DB の現在状態を渡して呼ぶ。
 *
 * 守る不変条件:
 * 1. ロック中は解除条件に影響するタスクの追加・削除をしない（有効タスクが0件のときの追加だけは例外）
 * 2. 有効タスクが1件以上あるなら、自己申告で解除できるタスクが必ず1件以上ある（計測不能による永久ロックを防ぐ）
 * 3. 学習アプリ（利用時間の計測対象）の集合とロック対象アプリの集合は交わらない
 */
object ChangePolicy {

    /** 自己申告だけで要求レベルを満たせるタスク */
    fun isSelfReportable(task: Task): Boolean = isSelfReportable(task.requiredStatus)

    fun isSelfReportable(requiredStatus: VerificationStatus): Boolean =
        VerificationStatus.SELF_REPORTED.satisfies(requiredStatus)

    /** 不変条件2: 0件、または自己申告で解除できるタスクを含む */
    fun hasSelfReportFallback(tasks: List<Task>): Boolean =
        tasks.isEmpty() || tasks.any { isSelfReportable(it) }

    fun canAddTask(
        locked: Boolean,
        activeTasks: List<Task>,
        newTask: Task,
        lockedPackages: Set<String>,
    ): ChangeResult {
        if (locked && activeTasks.isNotEmpty()) return reject(ChangeRejection.LOCKED)
        if (!hasSelfReportFallback(activeTasks + newTask)) return reject(ChangeRejection.NEEDS_SELF_REPORTED_TASK)
        if (newTask.targetPackage != null && newTask.targetPackage in lockedPackages) {
            return reject(ChangeRejection.STUDY_APP_IS_LOCKED)
        }
        return ChangeResult.Ok
    }

    fun canRemoveTask(locked: Boolean, activeTasks: List<Task>, taskId: Long): ChangeResult {
        if (locked) return reject(ChangeRejection.LOCKED)
        if (!hasSelfReportFallback(activeTasks.filter { it.id != taskId })) {
            return reject(ChangeRejection.NEEDS_SELF_REPORTED_TASK)
        }
        return ChangeResult.Ok
    }

    /** ロック対象の追加はロック中でも可（制限を強める方向なので） */
    fun canLockApp(packageName: String, activeTasks: List<Task>, exemptPackages: Set<String>): ChangeResult {
        if (packageName in exemptPackages) return reject(ChangeRejection.APP_IS_EXEMPT)
        if (activeTasks.any { it.targetPackage == packageName }) return reject(ChangeRejection.APP_IS_STUDY_TARGET)
        return ChangeResult.Ok
    }

    fun canUnlockApp(locked: Boolean): ChangeResult =
        if (locked) reject(ChangeRejection.LOCKED) else ChangeResult.Ok

    /** 学習アプリの集合（有効タスクの計測対象） */
    fun studyPackages(activeTasks: List<Task>): Set<String> = activeTasks.mapNotNull { it.targetPackage }.toSet()

    private fun reject(reason: ChangeRejection) = ChangeResult.Rejected(reason)
}
