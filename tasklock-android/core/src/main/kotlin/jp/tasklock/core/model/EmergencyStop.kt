package jp.tasklock.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * 緊急解除の記録（DESIGN.md §9.6-2「v3 の DB 設計」。DB の emergency_stops の1行）。
 * resumedAt が null なら有効（緊急解除中）。
 */
data class EmergencyStop(
    val id: Long = 0,
    /** 緊急解除をした日（DayBoundary.dayOf(stoppedAt)）。今月の回数はこの日で数える */
    val day: LocalDate,
    val stoppedAt: Instant,
    /** 項目6の「ロックを再開する」の確定で復帰した時刻 */
    val resumedAt: Instant? = null,
    /** 再開の確定のときに任意で入力した理由 */
    val reason: String? = null,
) {
    init {
        require(resumedAt == null || !resumedAt.isBefore(stoppedAt)) { "resumedAt must not be before stoppedAt" }
    }

    val isActive: Boolean get() = resumedAt == null
}

/** 緊急解除の時点のロック対象（DB の emergency_stop_apps の1行）。時刻は持たず、[stopId] の [EmergencyStop] に従う */
data class EmergencyStopApp(
    val stopId: Long,
    val packageName: String,
    /** その時点の表示名 */
    val label: String,
)
