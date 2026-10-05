package jp.tasklock.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * 一時解除（DESIGN.md §9.6-2）。DB v2 の emergency_unlocks の1行に対応する
 * （記録上の旧称は「緊急解除」。表とエンティティの名前は v3 で揃える）。
 * タスク達成による解除（[UnlockGrant]）とは別に持ち、ブロック判定にだけ使う。変更可否の判定には使わない。
 */
data class TemporaryUnlock(
    val id: Long = 0,
    /** 解除を開始した日（DayBoundary.dayOf(startedAt)）。1日の回数はこの日で数える */
    val day: LocalDate,
    val startedAt: Instant,
    /** 開始時に決めた期限。後から書き換えない */
    val expiresAt: Instant,
) {
    init {
        require(expiresAt.isAfter(startedAt)) { "expiresAt must be after startedAt" }
    }

    fun isActiveAt(now: Instant): Boolean = !now.isBefore(startedAt) && now.isBefore(expiresAt)
}
