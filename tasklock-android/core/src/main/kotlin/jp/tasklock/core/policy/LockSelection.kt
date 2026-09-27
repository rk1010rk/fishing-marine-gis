package jp.tasklock.core.policy

import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.UnlockGrant
import java.time.Instant

/** 保存済みのロック対象と下書きの差分 */
data class LockSelectionDiff(val added: Set<String>, val removed: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    /** 追加を含む変更は確認画面を経由する。外すだけなら確認しない（DESIGN.md §9.6-6 決定事項3） */
    val needsConfirmation: Boolean get() = added.isNotEmpty()
}

/** 確認画面の「ロックに関する注意」 */
enum class LockNotice {
    NONE,

    /** ロック中に追加する。追加したアプリは今日のタスクを達成するまで外せない */
    ADDING_WHILE_LOCKED,

    /** ロックしていない状態で確定し、それによってロックが始まる */
    STARTS_LOCK,
}

/**
 * ロック対象の選択を「確定」で反映するための判定（DESIGN.md §9.6-6）。I/O を持たない純粋関数。
 * 個々の可否は [ChangePolicy] に委ね、ここでは差分の組み立てと、まとめて反映してよいかの判定だけを行う。
 */
object LockSelection {

    fun diff(saved: Set<String>, draft: Set<String>): LockSelectionDiff =
        LockSelectionDiff(added = draft - saved, removed = saved - draft)

    /**
     * 差分をまとめて反映してよいか。反映する**前**の状態で判定し、1件でも拒否があれば最初の拒否を返す
     * （呼び出し側は拒否のとき何も書き込まない）。
     */
    fun canApply(
        diff: LockSelectionDiff,
        lockedBefore: Boolean,
        activeTasks: List<Task>,
        exemptPackages: Set<String>,
    ): ChangeResult {
        if (diff.removed.isNotEmpty()) {
            val result = ChangePolicy.canUnlockApp(lockedBefore)
            if (result != ChangeResult.Ok) return result
        }
        for (packageName in diff.added.sorted()) {
            val result = ChangePolicy.canLockApp(packageName, activeTasks, exemptPackages)
            if (result != ChangeResult.Ok) return result
        }
        return ChangeResult.Ok
    }

    /** 確認画面に出す注意。ロック中かどうかは [BlockSnapshot.isLockedAt] で反映前後を比べて求める */
    fun notice(saved: Set<String>, diff: LockSelectionDiff, grant: UnlockGrant?, now: Instant): LockNotice {
        if (diff.added.isEmpty()) return LockNotice.NONE
        if (BlockSnapshot(saved, grant).isLockedAt(now)) return LockNotice.ADDING_WHILE_LOCKED
        val after = saved - diff.removed + diff.added
        return if (BlockSnapshot(after, grant).isLockedAt(now)) LockNotice.STARTS_LOCK else LockNotice.NONE
    }
}
