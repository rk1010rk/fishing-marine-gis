package jp.tasklock.core.verify

import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.VerificationMethod
import jp.tasklock.core.model.VerificationStatus
import java.time.Duration
import java.time.Instant

/** 読書ページの整合性チェック結果 */
sealed interface ReadingCheck {
    data object Ok : ReadingCheck
    data class Mismatch(val message: String) : ReadingCheck
}

/**
 * Completion に対する Verification を生成する。保存やI/Oは行わない。
 */
object Verifier {

    fun selfReport(completion: Completion, now: Instant): Verification =
        Verification(
            completionId = completion.id,
            status = VerificationStatus.SELF_REPORTED,
            method = VerificationMethod.SELF_REPORT,
            data = mapOf("reported" to completion.reportedValue.toString()),
            verifiedAt = now,
        )

    /**
     * 学習アプリの利用時間による検証。目標以上で VERIFIED、1分以上だが未達で PARTIAL、0分なら UNVERIFIED。
     */
    fun appUsage(task: Task, completion: Completion, measured: Duration, now: Instant): Verification {
        val minutes = measured.toMinutes()
        val status = when {
            minutes >= task.targetValue -> VerificationStatus.VERIFIED
            minutes > 0 -> VerificationStatus.PARTIAL
            else -> VerificationStatus.UNVERIFIED
        }
        return Verification(
            completionId = completion.id,
            status = status,
            method = VerificationMethod.USAGE_STATS,
            data = mapOf(
                "package" to (task.targetPackage ?: ""),
                "measuredMinutes" to minutes.toString(),
                "targetMinutes" to task.targetValue.toString(),
            ),
            verifiedAt = now,
        )
    }

    /**
     * 読書: 前回の終了ページ・今回の開始/終了ページ・申告ページ数が矛盾しないか。
     * 客観データではないため、整合していても SELF_REPORTED のまま扱う。
     */
    fun checkReading(previousEndPage: Int?, startPage: Int, endPage: Int, reportedPages: Int): ReadingCheck {
        if (startPage <= 0 || endPage <= 0) return ReadingCheck.Mismatch("ページ番号は1以上で入力してください")
        if (endPage < startPage) return ReadingCheck.Mismatch("終了ページが開始ページより前になっています")
        val span = endPage - startPage + 1
        if (span != reportedPages) {
            return ReadingCheck.Mismatch("${startPage}〜${endPage}ページは${span}ページ分です（申告: ${reportedPages}ページ）")
        }
        if (previousEndPage != null && startPage > previousEndPage + 1) {
            return ReadingCheck.Mismatch("前回は${previousEndPage}ページまででした。開始ページを確認してください")
        }
        return ReadingCheck.Ok
    }
}
