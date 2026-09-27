package jp.tasklock.core.verify

import java.time.Duration
import java.time.Instant

/**
 * UsageStatsManager.queryEvents() の結果をプラットフォーム非依存の形にしたもの。
 */
data class UsageEvent(val timestamp: Instant, val packageName: String?, val kind: Kind) {
    enum class Kind {
        /** ACTIVITY_RESUMED (旧 MOVE_TO_FOREGROUND) */
        FOREGROUND,

        /** ACTIVITY_PAUSED (旧 MOVE_TO_BACKGROUND) */
        BACKGROUND,

        /** 画面OFF・シャットダウン等。全アプリの前面状態を打ち切る */
        END_ALL,
    }
}

/**
 * 対象アプリの前面表示時間を合算する。
 *
 * 前提: 「前面に表示されていた時間」であり「勉強していた時間」ではない。
 * - 窓の開始前から前面にいた場合（最初のイベントが BACKGROUND）は窓の開始から数える
 * - 窓の終了時点でまだ前面にいる場合は [windowEnd] まで数える
 */
object UsageTimeCalculator {

    fun foregroundTime(
        events: List<UsageEvent>,
        packageName: String,
        windowStart: Instant,
        windowEnd: Instant,
    ): Duration {
        var total = Duration.ZERO
        // 同一アプリ内の画面遷移では「B RESUMED → A PAUSED」の順に来ることがあるため、
        // 前面にある Activity 数を数えて 0↔1 の遷移だけを区間の開始・終了とみなす
        var depth = 0
        var openedAt: Instant? = null
        var sawAny = false

        fun close(at: Instant) {
            openedAt?.let { total += Duration.between(it, at) }
            openedAt = null
            depth = 0
        }

        for (e in events.sortedBy { it.timestamp }) {
            if (e.timestamp.isBefore(windowStart) || e.timestamp.isAfter(windowEnd)) continue
            val isTarget = e.packageName == packageName
            when {
                e.kind == UsageEvent.Kind.END_ALL -> close(e.timestamp)
                !isTarget -> Unit
                e.kind == UsageEvent.Kind.FOREGROUND -> {
                    if (depth == 0) openedAt = e.timestamp
                    depth++
                    sawAny = true
                }
                e.kind == UsageEvent.Kind.BACKGROUND -> {
                    if (depth == 0 && !sawAny) {
                        // 窓の開始前から前面にいた
                        total += Duration.between(windowStart, e.timestamp)
                    } else if (depth > 0) {
                        depth--
                        if (depth == 0) close(e.timestamp)
                    }
                    sawAny = true
                }
            }
        }
        openedAt?.let { total += Duration.between(it, windowEnd) }
        return total
    }
}
