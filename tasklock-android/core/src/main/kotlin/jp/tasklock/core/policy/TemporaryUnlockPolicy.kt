package jp.tasklock.core.policy

import jp.tasklock.core.model.TemporaryUnlock
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.time.DayBoundary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 一時解除を開始できない理由。UI にはそのまま [message] を表示する */
enum class TemporaryUnlockRejection(val message: String) {
    NOT_LOCKED("ロック中ではないため、一時解除は使えません"),
    TASK_UNLOCKED("今日はタスク達成で解除済みです"),
    ALREADY_ACTIVE("一時解除中です"),
    DAILY_LIMIT_REACHED("今日の一時解除は使い切りました（0時に戻ります）"),
}

sealed interface TemporaryUnlockDecision {
    /** 開始してよい。[unlock] は保存する値（id は未採番） */
    data class Allowed(val unlock: TemporaryUnlock) : TemporaryUnlockDecision
    data class Rejected(val reason: TemporaryUnlockRejection) : TemporaryUnlockDecision
}

/**
 * 一時解除の開始可否（DESIGN.md §9.6-2「一時解除と緊急解除」）。I/O を持たない純粋関数で、
 * Repository がトランザクション内で DB の現在状態を読み直して渡す（回数の判定と記録を分けない）。
 */
object TemporaryUnlockPolicy {
    /** 有効時間。利用者は変更できない */
    val DURATION: Duration = Duration.ofMinutes(10)

    /** 1日に開始できる回数。開始した時点で1回消費し、早く終えても戻さない */
    const val DAILY_LIMIT = 2

    /**
     * @param lockedPackages 現在のロック対象
     * @param grant タスク達成による解除の最新の記録
     * @param latest 一時解除の最新の記録（期限の最も遅いもの）
     * @param startedToday 今日（[DayBoundary.dayOf] の [now] の日）に開始した一時解除の件数
     */
    fun decide(
        now: Instant,
        boundary: DayBoundary,
        lockedPackages: Set<String>,
        grant: UnlockGrant?,
        latest: TemporaryUnlock?,
        startedToday: Int,
    ): TemporaryUnlockDecision {
        if (lockedPackages.isEmpty()) return reject(TemporaryUnlockRejection.NOT_LOCKED)
        if (grant?.isActiveAt(now) == true) return reject(TemporaryUnlockRejection.TASK_UNLOCKED)
        if (latest?.isActiveAt(now) == true) return reject(TemporaryUnlockRejection.ALREADY_ACTIVE)
        if (startedToday >= DAILY_LIMIT) return reject(TemporaryUnlockRejection.DAILY_LIMIT_REACHED)
        return TemporaryUnlockDecision.Allowed(
            TemporaryUnlock(day = boundary.dayOf(now), startedAt = now, expiresAt = now.plus(DURATION)),
        )
    }

    /** 今日あと何回開始できるか */
    fun remainingToday(startedToday: Int): Int = (DAILY_LIMIT - startedToday).coerceAtLeast(0)

    /** [day] が属する暦の月の初日と末日（「今月◯回目」の集計範囲。両端を含む） */
    fun monthRange(day: LocalDate): Pair<LocalDate, LocalDate> {
        val first = day.withDayOfMonth(1)
        return first to first.plusMonths(1).minusDays(1)
    }

    private fun reject(reason: TemporaryUnlockRejection) = TemporaryUnlockDecision.Rejected(reason)
}
