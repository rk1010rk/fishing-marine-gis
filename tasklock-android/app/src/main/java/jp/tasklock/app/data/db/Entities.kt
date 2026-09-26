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
