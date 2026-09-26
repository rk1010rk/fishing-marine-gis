package jp.tasklock.app.data

import androidx.room.withTransaction
import jp.tasklock.app.data.db.AppDatabase
import jp.tasklock.app.data.db.LockedAppEntity
import jp.tasklock.app.data.db.UnlockGrantEntity
import jp.tasklock.app.platform.UsageStatsReader
import jp.tasklock.core.lock.BlockSnapshot
import jp.tasklock.core.lock.LockEvaluator
import jp.tasklock.core.lock.UnlockDecision
import jp.tasklock.core.model.Completion
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.VerificationMethod
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.model.bestStatus
import jp.tasklock.core.template.TaskTemplate
import jp.tasklock.core.time.DayBoundary
import jp.tasklock.core.verify.ReadingCheck
import jp.tasklock.core.verify.Verifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

data class TaskProgress(
    val task: Task,
    val completedToday: Boolean,
    val bestStatus: VerificationStatus,
)

data class TodayState(
    val day: LocalDate,
    val tasks: List<TaskProgress>,
    val lockedApps: List<LockedAppEntity>,
    val grant: UnlockGrant?,
)

/** タスク完了画面に渡す入力。タスク種別により使うフィールドが異なる */
data class CompletionInput(
    val reportedValue: Int,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val note: String? = null,
)

sealed interface CompletionResult {
    data class Saved(val status: VerificationStatus, val unlocked: Boolean, val measuredMinutes: Long?) : CompletionResult
    data class Rejected(val message: String) : CompletionResult
}

class TaskLockRepository(
    private val db: AppDatabase,
    private val usageStats: UsageStatsReader,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val boundary get() = DayBoundary(clock.zone)
    private val evaluator get() = LockEvaluator(boundary)

    private val currentDay = MutableStateFlow(today())

    fun today(): LocalDate = boundary.dayOf(clock.instant())

    /** 画面復帰時などに呼び、日付の切り替わりを反映する */
    fun refreshDay() {
        currentDay.value = today()
    }

    /**
     * アクセシビリティサービスが同期的に参照するスナップショット。
     * 起動直後の読み込み完了までは EMPTY（=ブロックしない）になる。
     */
    val blockSnapshot: StateFlow<BlockSnapshot> =
        combine(db.lockedAppDao().observeAll(), db.unlockGrantDao().observeLatest()) { apps, grant ->
            BlockSnapshot(apps.map { it.packageName }.toSet(), grant?.toModel())
        }.stateIn(scope, SharingStarted.Eagerly, BlockSnapshot.EMPTY)

    @OptIn(ExperimentalCoroutinesApi::class)
    val todayState: Flow<TodayState> = currentDay.flatMapLatest { day ->
        val key = day.toString()
        combine(
            db.taskDao().observeActive(),
            db.completionDao().observeForDay(key),
            db.verificationDao().observeForDay(key),
            db.lockedAppDao().observeAll(),
            db.unlockGrantDao().observeLatest(),
        ) { tasks, completions, verifications, apps, grant ->
            val byTask = completions.groupBy { it.taskId }
            val byCompletion = verifications.groupBy { it.completionId }
            val progress = tasks.map { t ->
                val vs = byTask[t.id].orEmpty().flatMap { c -> byCompletion[c.id].orEmpty() }.map { it.toModel() }
                val best = vs.bestStatus()
                val model = t.toModel()
                TaskProgress(model, completedToday = best.satisfies(model.requiredStatus), bestStatus = best)
            }
            TodayState(day, progress, apps, grant?.toModel()?.takeIf { it.isActiveAt(clock.instant()) })
        }
    }

    // ---- タスク設定 ----

    suspend fun addTask(template: TaskTemplate, title: String, target: Int, targetPackage: String?): Long =
        db.taskDao().insert(
            Task(
                templateId = template.id,
                title = title,
                category = template.category,
                unit = template.unit,
                targetValue = target,
                verificationPolicy = template.policy,
                requiredStatus = template.requiredStatus,
                targetPackage = targetPackage,
                createdAt = clock.instant(),
            ).toEntity(),
        )

    suspend fun getTask(id: Long): Task? = db.taskDao().getById(id)?.toModel()

    /** ロック中に削除すると簡単に回避できてしまうため、解除中のみ許可する */
    suspend fun deactivateTask(id: Long): Boolean {
        if (isLockedNow()) return false
        db.taskDao().deactivate(id)
        return true
    }

    // ---- ロック対象アプリ ----

    suspend fun addLockedApp(packageName: String, label: String) =
        db.lockedAppDao().insert(LockedAppEntity(packageName, label, clock.millis()))

    /** 追加はいつでも可能。外すのは解除中のみ（ロック中に外せると意味がないため） */
    suspend fun removeLockedApp(packageName: String): Boolean {
        if (isLockedNow()) return false
        db.lockedAppDao().delete(packageName)
        return true
    }

    fun isLockedNow(): Boolean {
        val snap = blockSnapshot.value
        return snap.lockedPackages.isNotEmpty() && snap.grant?.isActiveAt(clock.instant()) != true
    }

    // ---- 完了 → 検証 → 解除 ----

    suspend fun previousEndPage(taskId: Long): Int? = db.completionDao().lastEndPage(taskId)

    fun measureAppUsageToday(task: Task): Duration {
        val pkg = task.targetPackage ?: return Duration.ZERO
        val day = today()
        return usageStats.foregroundTime(pkg, boundary.startOf(day), clock.instant())
    }

    suspend fun complete(task: Task, input: CompletionInput): CompletionResult {
        val now = clock.instant()
        val day = boundary.dayOf(now)

        if (task.category == TaskCategory.READING && input.startPage != null && input.endPage != null) {
            val check = Verifier.checkReading(previousEndPage(task.id), input.startPage, input.endPage, input.reportedValue)
            if (check is ReadingCheck.Mismatch) return CompletionResult.Rejected(check.message)
        }
        if (task.verificationPolicy == VerificationPolicy.SELF_REPORT && input.reportedValue < task.targetValue) {
            return CompletionResult.Rejected("目標（${task.targetValue}${task.unit.label}）に届いていません")
        }
        // I/O を伴う計測はトランザクションの外で行う
        val measured = if (task.verificationPolicy == VerificationPolicy.APP_USAGE) measureAppUsageToday(task) else null

        return db.withTransaction {
            val todays = db.completionDao().forDay(day.toString())
            // アプリ利用時間の再確認は、同日の既存 Completion に Verification を追加する
            val reuse = if (measured != null) todays.lastOrNull { it.taskId == task.id }?.toModel() else null
            val completion = reuse ?: Completion(
                taskId = task.id,
                day = day,
                reportedValue = input.reportedValue,
                startPage = input.startPage,
                endPage = input.endPage,
                note = input.note,
                completedAt = now,
            ).let { it.copy(id = db.completionDao().insert(it.toEntity())) }

            val startPage = input.startPage
            val endPage = input.endPage
            val verification = when (task.verificationPolicy) {
                VerificationPolicy.APP_USAGE -> Verifier.appUsage(task, completion, measured ?: Duration.ZERO, now)
                VerificationPolicy.SELF_REPORT ->
                    if (task.category == TaskCategory.READING && startPage != null && endPage != null) {
                        // 整合性チェック済みの自己申告。客観データではないので SELF_REPORTED のまま
                        Verifier.selfReport(completion, now).copy(
                            method = VerificationMethod.READING_CONSISTENCY,
                            data = mapOf(
                                "startPage" to startPage.toString(),
                                "endPage" to endPage.toString(),
                                "consistent" to "true",
                            ),
                        )
                    } else {
                        Verifier.selfReport(completion, now)
                    }
            }
            db.verificationDao().insert(verification.toEntity())

            val unlocked = evaluateAndGrant(day)
            CompletionResult.Saved(verification.status, unlocked, measured?.toMinutes())
        }
    }

    /** 当日のデータでロック条件を評価し、満たしていれば解除記録を作る。既に解除済みなら何もしない */
    private suspend fun evaluateAndGrant(day: LocalDate): Boolean {
        val key = day.toString()
        if (db.unlockGrantDao().latestForDay(key) != null) return true

        val rule = db.lockRuleDao().getActive()?.toModel()
        val tasks = db.taskDao().getActive().map { it.toModel() }
        val completions = db.completionDao().forDay(key).map { it.toModel() }
        val verifications: List<Verification> =
            db.verificationDao().forCompletions(completions.map { it.id }).map { it.toModel() }

        val decision = evaluator.evaluate(rule, day, tasks, completions, verifications)
        if (decision !is UnlockDecision.Unlock || rule == null) return false
        db.unlockGrantDao().insert(
            UnlockGrantEntity(
                day = key,
                ruleId = rule.id,
                completionId = decision.completionId,
                grantedAt = clock.millis(),
                expiresAt = decision.expiresAt.toEpochMilli(),
            ),
        )
        return true
    }
}
