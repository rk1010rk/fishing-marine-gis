package jp.tasklock.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 列挙値はすべて文字列（enum.name / VerificationMethod.id）で保存する。
 * これにより PHOTO_VERIFIED 等の値や新しい検証方式を追加してもスキーマ変更・マイグレーションが不要。
 * 日時は epoch millis、日付（「どの日の完了か」）は ISO-8601 文字列（yyyy-MM-dd）。
 */

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: String?,
    val title: String,
    val category: String,
    val unit: String,
    val targetValue: Int,
    val verificationPolicy: String,
    val requiredStatus: String,
    val targetPackage: String?,
    val active: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "completions",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"])],
    indices = [Index("taskId"), Index("day")],
)
data class CompletionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val day: String,
    val reportedValue: Int,
    val startPage: Int?,
    val endPage: Int?,
    val note: String?,
    val completedAt: Long,
)

@Entity(
    tableName = "verifications",
    foreignKeys = [
        ForeignKey(
            entity = CompletionEntity::class,
            parentColumns = ["id"],
            childColumns = ["completionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("completionId")],
)
data class VerificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val completionId: Long,
    val status: String,
    val method: String,
    /** 方式固有データ（JSONオブジェクト）。画像そのものは保存しない */
    val dataJson: String,
    val modelVersion: String?,
    val verifiedAt: Long,
)

@Entity(tableName = "lock_rules")
data class LockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val paramsJson: String,
    val active: Boolean,
)

@Entity(tableName = "locked_apps")
data class LockedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val addedAt: Long,
)

@Entity(tableName = "unlock_grants", indices = [Index("day")])
data class UnlockGrantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val ruleId: Long,
    val completionId: Long,
    val grantedAt: Long,
    val expiresAt: Long,
)

/**
 * 一時解除の記録（DESIGN.md §9.6-2）。v2 までの表の名前は emergency_unlocks（記録上の旧称は「緊急解除」）で、
 * v3 で temporary_unlocks に揃えた（AppDatabase の Migration2To3）。
 * タスク達成による解除（unlock_grants）とは別に持ち、変更可否の判定（ChangePolicy・isLockedNow）には使わない。
 * 外部キーは持たない。:core のモデルは TemporaryUnlock
 */
@Entity(tableName = "temporary_unlocks", indices = [Index("day")])
data class TemporaryUnlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 解除を開始した日（DayBoundary.dayOf(startedAt)）。1日の回数はこの列で数える */
    val day: String,
    val startedAt: Long,
    /** 開始時に決めた期限。期限前の終了を後で採用しても書き換えない */
    val expiresAt: Long,
)

/**
 * 緊急解除の記録（DESIGN.md §9.6-2「v3 の DB 設計」）。resumedAt が null の行が有効な緊急解除で、
 * 常に0件か1件（DB の制約ではなく、開始と再開のトランザクションで保証する）
 */
@Entity(tableName = "emergency_stops", indices = [Index("day")])
data class EmergencyStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 緊急解除をした日（DayBoundary.dayOf(stoppedAt)）。今月の回数はこの列で数える */
    val day: String,
    val stoppedAt: Long,
    /** 項目6の「ロックを再開する」の確定で復帰した時刻。null の間は緊急解除中 */
    val resumedAt: Long? = null,
    /** 再開の確定のときに任意で入力した理由 */
    val reason: String? = null,
)

/** 緊急解除の時点のロック対象（復元候補）。再開した後も履歴として残す */
@Entity(
    tableName = "emergency_stop_apps",
    primaryKeys = ["stopId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = EmergencyStopEntity::class,
            parentColumns = ["id"],
            childColumns = ["stopId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("stopId")],
)
data class EmergencyStopAppEntity(
    val stopId: Long,
    val packageName: String,
    /** その時点の表示名 */
    val label: String,
)
