package jp.tasklock.core.model

import java.time.Instant

/**
 * 検証レベル。Completion(完了申告)とは独立して保持する。
 *
 * DBには [name] を文字列で保存するため、値の追加はスキーマ変更なしで行える。
 * [rank] は「ロック解除に必要な最低レベル」の比較にのみ使う。
 */
enum class VerificationStatus(val rank: Int) {
    /** 完了記録はあるが検証情報なし */
    UNVERIFIED(0),

    /** 自己申告のみ */
    SELF_REPORTED(1),

    /** 一部条件のみ客観的に検証 */
    PARTIAL(2),

    /** 撮影画像をAIで判定（Phase 1では未使用。将来拡張用） */
    PHOTO_VERIFIED(3),

    /** OS等の客観データで条件達成を確認 */
    VERIFIED(4);

    fun satisfies(required: VerificationStatus): Boolean = rank >= required.rank

    companion object {
        /** 未知の値（新しいバージョンで書かれたDB等）は安全側に倒して UNVERIFIED とする */
        fun parse(value: String): VerificationStatus =
            entries.firstOrNull { it.name == value } ?: UNVERIFIED
    }
}

/**
 * 検証方式。将来の追加に備え enum ではなく文字列IDの値クラスにしている。
 */
@JvmInline
value class VerificationMethod(val id: String) {
    companion object {
        val SELF_REPORT = VerificationMethod("self_report")
        val USAGE_STATS = VerificationMethod("usage_stats")
        val READING_CONSISTENCY = VerificationMethod("reading_consistency")
        val HEALTH_CONNECT = VerificationMethod("health_connect")
        val CAMERA_AI = VerificationMethod("camera_ai")
    }
}

/**
 * 1件の検証結果。1つの Completion に複数の Verification を紐付けられる
 * （例: 読書の整合性チェック + 将来の写真検証）。
 */
data class Verification(
    val id: Long = 0,
    val completionId: Long,
    val status: VerificationStatus,
    val method: VerificationMethod,
    /** 方式固有の検証データ。画像そのものは保存しない方針。 */
    val data: Map<String, String> = emptyMap(),
    /** AI判定などモデルを使う方式でのみ設定 */
    val modelVersion: String? = null,
    val verifiedAt: Instant,
)

/** 複数の検証結果のうち最も強いレベルを返す。検証が1件も無ければ UNVERIFIED。 */
fun List<Verification>.bestStatus(): VerificationStatus =
    maxByOrNull { it.status.rank }?.status ?: VerificationStatus.UNVERIFIED
