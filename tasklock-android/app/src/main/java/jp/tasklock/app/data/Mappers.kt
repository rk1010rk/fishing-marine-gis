package jp.tasklock.app.data

import jp.tasklock.app.data.db.CompletionEntity
import jp.tasklock.app.data.db.LockRuleEntity
import jp.tasklock.app.data.db.TaskEntity
import jp.tasklock.app.data.db.TemporaryUnlockEntity
import jp.tasklock.app.data.db.UnlockGrantEntity
import jp.tasklock.app.data.db.VerificationEntity
import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.LockRule
import jp.tasklock.core.model.TargetUnit
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.TemporaryUnlock
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.VerificationMethod
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

fun TaskEntity.toModel() = Task(
    id = id,
    templateId = templateId,
    title = title,
    category = TaskCategory.valueOf(category),
    unit = TargetUnit.valueOf(unit),
    targetValue = targetValue,
    verificationPolicy = VerificationPolicy.valueOf(verificationPolicy),
    requiredStatus = VerificationStatus.parse(requiredStatus),
    targetPackage = targetPackage,
    active = active,
    createdAt = Instant.ofEpochMilli(createdAt),
)

fun Task.toEntity() = TaskEntity(
    id = id,
    templateId = templateId,
    title = title,
    category = category.name,
    unit = unit.name,
    targetValue = targetValue,
    verificationPolicy = verificationPolicy.name,
    requiredStatus = requiredStatus.name,
    targetPackage = targetPackage,
    active = active,
    createdAt = createdAt.toEpochMilli(),
)

fun CompletionEntity.toModel() = Completion(
    id = id,
    taskId = taskId,
    day = LocalDate.parse(day),
    reportedValue = reportedValue,
    startPage = startPage,
    endPage = endPage,
    note = note,
    completedAt = Instant.ofEpochMilli(completedAt),
)

fun Completion.toEntity() = CompletionEntity(
    id = id,
    taskId = taskId,
    day = day.toString(),
    reportedValue = reportedValue,
    startPage = startPage,
    endPage = endPage,
    note = note,
    completedAt = completedAt.toEpochMilli(),
)

fun VerificationEntity.toModel() = Verification(
    id = id,
    completionId = completionId,
    status = VerificationStatus.parse(status),
    method = VerificationMethod(method),
    data = jsonToMap(dataJson),
    modelVersion = modelVersion,
    verifiedAt = Instant.ofEpochMilli(verifiedAt),
)

fun Verification.toEntity() = VerificationEntity(
    id = id,
    completionId = completionId,
    status = status.name,
    method = method.id,
    dataJson = JSONObject(data).toString(),
    modelVersion = modelVersion,
    verifiedAt = verifiedAt.toEpochMilli(),
)

fun LockRuleEntity.toModel() = LockRule(id = id, type = type, params = jsonToMap(paramsJson), active = active)

fun UnlockGrantEntity.toModel() = UnlockGrant(
    id = id,
    day = LocalDate.parse(day),
    ruleId = ruleId,
    completionId = completionId,
    grantedAt = Instant.ofEpochMilli(grantedAt),
    expiresAt = Instant.ofEpochMilli(expiresAt),
)

/** temporary_unlocks（v2 までは emergency_unlocks）の1行。一時解除（DESIGN.md §9.6-2） */
fun TemporaryUnlockEntity.toModel() = TemporaryUnlock(
    id = id,
    day = LocalDate.parse(day),
    startedAt = Instant.ofEpochMilli(startedAt),
    expiresAt = Instant.ofEpochMilli(expiresAt),
)

fun TemporaryUnlock.toEntity() = TemporaryUnlockEntity(
    id = id,
    day = day.toString(),
    startedAt = startedAt.toEpochMilli(),
    expiresAt = expiresAt.toEpochMilli(),
)

private fun jsonToMap(json: String): Map<String, String> {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.optString(it) }
}
