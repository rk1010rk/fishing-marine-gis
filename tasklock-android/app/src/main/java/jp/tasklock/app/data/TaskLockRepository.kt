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
import jp.tasklock.core.model.TemporaryUnlock
import jp.tasklock.core.model.UnlockGrant
import jp.tasklock.core.model.Verification
import jp.tasklock.core.model.VerificationMethod
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.model.bestStatus
import jp.tasklock.core.policy.ChangePolicy
import jp.tasklock.core.policy.ChangeResult
import jp.tasklock.core.policy.LockNotice
import jp.tasklock.core.policy.LockSelection
import jp.tasklock.core.policy.LockSelectionDiff
import jp.tasklock.core.policy.TemporaryUnlockDecision
import jp.tasklock.core.policy.TemporaryUnlockPolicy
import jp.tasklock.core.policy.TemporaryUnlockRejection
import jp.tasklock.core.template.TaskTemplate
import jp.tasklock.core.time.DayBoundary
import jp.tasklock.core.verify.ReadingCheck
import jp.tasklock.core.verify.Verifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    /** 有効な一時解除（無ければ null）。ブロックだけを止め、[locked] には影響しない（DESIGN.md §9.6-2） */
    val temporary: TemporaryUnlock? = null,
    /** この状態を作った時刻（一時解除の残り時間の計算に使う） */
    val asOf: Instant = Instant.EPOCH,
) {
    /** 画面表示用。変更可否の最終判定は Repository が DB から行う。一時解除中も「ロック中」 */
    val locked: Boolean get() = lockedApps.isNotEmpty() && grant == null

    /** 一時解除の残り時間（分、切り上げ）。一時解除中でなければ null */
    val temporaryRemainingMinutes: Long?
        get() = temporary?.let { t ->
            val millis = Duration.between(asOf, t.expiresAt).toMillis().coerceAtLeast(0)
            (millis + 59_999) / 60_000
        }

    val studyPackages: Set<String> get() = ChangePolicy.studyPackages(tasks.map { it.task })

    val hasSelfReportTask: Boolean get() = tasks.any { ChangePolicy.isSelfReportable(it.task) }
}

/** タスク完了画面に渡す入力。タスク種別により使うフィールドが異なる */
data class CompletionInput(
    val reportedValue: Int,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val note: String? = null,
)

/**
 * ブロック画面に出す一時解除の情報。表示用の事前判定で、開始できるかどうかは
 * [TaskLockRepository.startTemporaryUnlock] がトランザクション内で判定し直す
 */
data class TemporaryUnlockStatus(
    /** 開始できない理由。null なら開始できる */
    val rejection: TemporaryUnlockRejection?,
    /** 今日あと何回開始できるか */
    val remainingToday: Int,
    /** 次に開始すると今月何回目になるか */
    val nextNumberThisMonth: Int,
)

sealed interface CompletionResult {
    data class Saved(val status: VerificationStatus, val unlocked: Boolean, val measuredMinutes: Long?) : CompletionResult
    data class Rejected(val message: String) : CompletionResult
}

class TaskLockRepository(
    private val db: AppDatabase,
    private val usageStats: UsageStatsReader,
    /** 実行時点のホーム・電話・設定・自アプリ。既定アプリの変更に追従するため毎回問い合わせる */
    private val exemptPackages: () -> Set<String>,
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
     * アクセシビリティサービスが参照するスナップショット。
     * **null は「DB から未ロード」**であり「ロック対象なし」ではない。初回ロード後は null に戻らない。
     */
    val blockSnapshot: StateFlow<BlockSnapshot?> =
        combine(
            db.lockedAppDao().observeAll(),
            db.unlockGrantDao().observeLatest(),
            db.emergencyUnlockDao().observeLatest(),
        ) { apps, grant, temporary ->
            BlockSnapshot(apps.map { it.packageName }.toSet(), grant?.toModel(), temporary?.toModel())
        }.stateIn(scope, SharingStarted.Eagerly, null)

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
        }.combine(temporaryTicks) { state, tick -> state.copy(temporary = tick.active, asOf = tick.at) }
    }

    private data class TemporaryTick(val active: TemporaryUnlock?, val at: Instant)

    /**
     * 有効な一時解除と現在時刻。期限が来ても DB は変わらず Flow が発火しないため、
     * 一時解除が有効な間は30秒ごとと期限の時刻に自分で発火する（ホームの残り時間と、期限切れでの表示の切り替え）
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val temporaryTicks: Flow<TemporaryTick> =
        db.emergencyUnlockDao().observeLatest().flatMapLatest { entity ->
            val unlock = entity?.toModel()
            flow {
                while (true) {
                    val now = clock.instant()
                    if (unlock == null || !unlock.isActiveAt(now)) {
                        emit(TemporaryTick(null, now))
                        break
                    }
                    emit(TemporaryTick(unlock, now))
                    val untilExpiry = Duration.between(now, unlock.expiresAt).toMillis()
                    delay(minOf(TICK_MILLIS, untilExpiry + 1).coerceAtLeast(1))
                }
            }
        }

    // ---- タスク設定 ----
    // 可否判定は ChangePolicy（:core）に集約し、ここでは DB の現在状態をトランザクション内で読んで渡す。
    // メモリ上の blockSnapshot は起動直後に未ロードのため、変更可否の判定には使わない。

    suspend fun addTask(template: TaskTemplate, title: String, target: Int, targetPackage: String?): ChangeResult {
        val task = Task(
            templateId = template.id,
            title = title,
            category = template.category,
            unit = template.unit,
            targetValue = target,
            verificationPolicy = template.policy,
            requiredStatus = template.requiredStatus,
            targetPackage = targetPackage,
            createdAt = clock.instant(),
        )
        return db.withTransaction {
            val result = ChangePolicy.canAddTask(
                locked = isLockedNow(),
                activeTasks = activeTasks(),
                newTask = task,
                lockedPackages = db.lockedAppDao().getPackages().toSet(),
            )
            if (result == ChangeResult.Ok) db.taskDao().insert(task.toEntity())
            result
        }
    }

    suspend fun getTask(id: Long): Task? = db.taskDao().getById(id)?.toModel()

    suspend fun deactivateTask(id: Long): ChangeResult = db.withTransaction {
        val result = ChangePolicy.canRemoveTask(isLockedNow(), activeTasks(), id)
        if (result == ChangeResult.Ok) db.taskDao().deactivate(id)
        result
    }

    // ---- ロック対象アプリ ----
    // 画面のチェックは下書きで、DB に書き込むのは applyLockSelection だけ（DESIGN.md §9.6-6）。

    /**
     * 確定された変更をまとめて反映する。[added] は追加するパッケージとその表示名、[removed] は外すパッケージ。
     * トランザクション内で DB の現在状態を読み直し、反映前の状態で全件を判定する。
     * 1件でも拒否されたら何も書き込まずに最初の拒否を返す（全件反映か、何もしないかのどちらか）。
     */
    suspend fun applyLockSelection(added: Map<String, String>, removed: Set<String>): ChangeResult {
        val exempt = exemptPackages()
        return db.withTransaction {
            val current = db.lockedAppDao().getPackages().toSet()
            // 画面を開いている間に DB が変わっていても、実際に変わる分だけを判定・反映する
            val diff = LockSelectionDiff(added = added.keys - current, removed = removed intersect current)
            val result = LockSelection.canApply(diff, isLockedNow(), activeTasks(), exempt)
            if (result == ChangeResult.Ok) {
                diff.removed.forEach { db.lockedAppDao().delete(it) }
                diff.added.forEach { pkg ->
                    db.lockedAppDao().insert(LockedAppEntity(pkg, added.getValue(pkg), clock.millis()))
                }
            }
            result
        }
    }

    /** 確認画面の「ロックに関する注意」。表示用で、反映の可否は [applyLockSelection] が改めて判定する */
    suspend fun previewLockNotice(diff: LockSelectionDiff): LockNotice =
        LockSelection.notice(
            saved = db.lockedAppDao().getPackages().toSet(),
            diff = diff,
            grant = db.unlockGrantDao().getLatest()?.toModel(),
            now = clock.instant(),
        )

    /** DB の現在状態から判定する（ロック対象が1つ以上あり、有効な解除記録が無い） */
    suspend fun isLockedNow(): Boolean =
        BlockSnapshot(
            lockedPackages = db.lockedAppDao().getPackages().toSet(),
            grant = db.unlockGrantDao().getLatest()?.toModel(),
        ).isLockedAt(clock.instant())

    private suspend fun activeTasks(): List<Task> = db.taskDao().getActive().map { it.toModel() }

    // ---- 一時解除（DESIGN.md §9.6-2） ----
    // 開始の可否は TemporaryUnlockPolicy（:core）が判定し、ここではトランザクション内で DB を読み直して渡す。
    // 判定と記録を同じトランザクションで行うことが回数制限の正しさの根拠で、画面側の二度押し防止は補助にすぎない。

    /** 一時解除を開始する。許可されたときだけ記録し、採番した id を付けて返す */
    suspend fun startTemporaryUnlock(): TemporaryUnlockDecision = db.withTransaction {
        when (val decision = decideTemporaryUnlock(clock.instant())) {
            is TemporaryUnlockDecision.Allowed -> {
                val id = db.emergencyUnlockDao().insert(decision.unlock.toEntity())
                TemporaryUnlockDecision.Allowed(decision.unlock.copy(id = id))
            }
            is TemporaryUnlockDecision.Rejected -> decision
        }
    }

    /** ブロック画面の表示用。開始の可否はここでは確定しない（[startTemporaryUnlock] が判定し直す） */
    suspend fun temporaryUnlockStatus(): TemporaryUnlockStatus = db.withTransaction {
        val now = clock.instant()
        val today = boundary.dayOf(now)
        val (from, to) = TemporaryUnlockPolicy.monthRange(today)
        TemporaryUnlockStatus(
            rejection = (decideTemporaryUnlock(now) as? TemporaryUnlockDecision.Rejected)?.reason,
            remainingToday = TemporaryUnlockPolicy.remainingToday(
                db.emergencyUnlockDao().countForDay(today.toString()),
            ),
            nextNumberThisMonth = db.emergencyUnlockDao().countBetween(from.toString(), to.toString()) + 1,
        )
    }

    /**
     * 開始した一時解除が [blockSnapshot]（アクセシビリティサービスが参照する）に反映されるのを待つ。
     * 反映されないまま対象アプリを開くと、古いスナップショットで再びブロックされるため。
     * それより新しい記録が現れた・期限が過ぎた・[TEMPORARY_SNAPSHOT_TIMEOUT_MILLIS] を過ぎた場合は待つのをやめる。
     * @return 反映されたら true。false でも記録自体は保存済み（開始の失敗ではない）
     */
    suspend fun awaitTemporaryUnlockInSnapshot(unlock: TemporaryUnlock): Boolean {
        val snapshot = withTimeoutOrNull(TEMPORARY_SNAPSHOT_TIMEOUT_MILLIS) {
            blockSnapshot.first { snap ->
                val current = snap?.temporary
                (current != null && current.id >= unlock.id) || !unlock.isActiveAt(clock.instant())
            }
        }
        return snapshot?.temporary?.id == unlock.id && unlock.isActiveAt(clock.instant())
    }

    private suspend fun decideTemporaryUnlock(now: Instant): TemporaryUnlockDecision =
        TemporaryUnlockPolicy.decide(
            now = now,
            boundary = boundary,
            lockedPackages = db.lockedAppDao().getPackages().toSet(),
            grant = db.unlockGrantDao().getLatest()?.toModel(),
            latest = db.emergencyUnlockDao().getLatest()?.toModel(),
            startedToday = db.emergencyUnlockDao().countForDay(boundary.dayOf(now).toString()),
        )

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

    private companion object {
        /** 一時解除中のホームの更新間隔（残り時間の表示） */
        const val TICK_MILLIS = 30_000L

        /** 開始した一時解除がスナップショットに反映されるのを待つ上限 */
        const val TEMPORARY_SNAPSHOT_TIMEOUT_MILLIS = 3_000L
    }
}
