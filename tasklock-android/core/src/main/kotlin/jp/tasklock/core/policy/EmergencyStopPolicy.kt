package jp.tasklock.core.policy

import jp.tasklock.core.model.EmergencyStop
import jp.tasklock.core.model.EmergencyStopApp
import jp.tasklock.core.time.DayBoundary
import java.time.Instant

/** 緊急解除を開始できない理由。UI にはそのまま [message] を表示する */
enum class EmergencyStopRejection(val message: String) {
    ALREADY_STOPPED("すでに緊急解除中です"),
    NOT_LOCKED("ロック中ではないため、緊急解除は使えません"),
}

sealed interface EmergencyStopDecision {
    /** 開始してよい。[stop] は保存する値（id は未採番） */
    data class Allowed(val stop: EmergencyStop) : EmergencyStopDecision
    data class Rejected(val reason: EmergencyStopRejection) : EmergencyStopDecision
}

/**
 * 緊急解除の判定（DESIGN.md §9.6-2「一時解除と緊急解除」「v3 の DB 設計」）。I/O を持たない純粋関数で、
 * Repository がトランザクション内で DB の現在状態を読み直して渡す。
 *
 * 有効な緊急解除（resumedAt が null）は通常0件か1件。DB の制約では保証しないため、2件以上ある異常な状態も扱う:
 * 開始は拒否し、再開ではすべてを再開済みにし、復元候補はすべての有効な記録から作る。
 */
object EmergencyStopPolicy {
    /** 再開の理由の上限（Unicode のコードポイント数） */
    const val REASON_MAX_LENGTH = 200

    /**
     * 開始の可否。拒否の順は ALREADY_STOPPED → NOT_LOCKED（緊急解除中はロック対象が空のため、通常は両方に当たる）。
     * @param lockedNow 変更可否の判定と同じ「ロック中」（isLockedNow()）。一時解除中も true
     * @param stops 緊急解除の記録。再開済みの記録が含まれていても判定には使わない
     */
    fun decideStart(now: Instant, boundary: DayBoundary, lockedNow: Boolean, stops: List<EmergencyStop>): EmergencyStopDecision {
        if (stops.any { it.isActive }) return EmergencyStopDecision.Rejected(EmergencyStopRejection.ALREADY_STOPPED)
        if (!lockedNow) return EmergencyStopDecision.Rejected(EmergencyStopRejection.NOT_LOCKED)
        return EmergencyStopDecision.Allowed(EmergencyStop(day = boundary.dayOf(now), stoppedAt = now))
    }

    /**
     * 項目6の確定を反映した後に、再開として resumedAt を記録する緊急解除の id。
     * 反映後のロック対象が空なら再開ではない。有効な記録が2件以上なら、すべてを再開済みにする
     */
    fun resumeTargets(stops: List<EmergencyStop>, lockedAfterApply: Set<String>): List<Long> {
        if (lockedAfterApply.isEmpty()) return emptyList()
        return stops.filter { it.isActive }.map { it.id }
    }

    /**
     * 「前のロック対象で再開」の下書きの初期値。有効なすべての緊急解除のロック対象をまとめ、
     * 同じパッケージが複数あれば、その緊急解除の stoppedAt が最も新しいものの表示名を使う。
     * アンインストール済み・除外アプリ・学習アプリは除く（確定は1件でも拒否されると何も反映しないため）。
     * 並びは表示名、次にパッケージ名の順
     */
    fun restoreCandidates(
        stops: List<EmergencyStop>,
        apps: List<EmergencyStopApp>,
        installed: Set<String>,
        exempt: Set<String>,
        studyPackages: Set<String>,
    ): List<EmergencyStopApp> {
        // 時刻は EmergencyStopApp には無いため、stopId で対応する EmergencyStop の stoppedAt を使う
        val activeStoppedAt: Map<Long, Instant> = stops.filter { it.isActive }.associate { it.id to it.stoppedAt }
        return apps
            .filter { it.stopId in activeStoppedAt }
            .filter { it.packageName in installed && it.packageName !in exempt && it.packageName !in studyPackages }
            .groupBy { it.packageName }
            .map { (_, sameApp) -> sameApp.maxWith(compareBy({ activeStoppedAt.getValue(it.stopId) }, { it.stopId })) }
            .sortedWith(compareBy({ it.label }, { it.packageName }))
    }

    /** 理由の正規化: 前後の空白（全角を含む）を除き、空なら null、[REASON_MAX_LENGTH] コードポイントを超えたら先頭だけにする */
    fun normalizeReason(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints <= REASON_MAX_LENGTH) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, REASON_MAX_LENGTH))
    }
}
