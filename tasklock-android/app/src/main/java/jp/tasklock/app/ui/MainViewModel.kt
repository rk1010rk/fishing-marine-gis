package jp.tasklock.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.data.CompletionInput
import jp.tasklock.app.data.CompletionResult
import jp.tasklock.app.data.TodayState
import jp.tasklock.app.platform.AccessibilityStatus
import jp.tasklock.app.platform.AppInfo
import jp.tasklock.core.model.Task
import jp.tasklock.core.policy.ChangeResult
import jp.tasklock.core.policy.ImportantApps
import jp.tasklock.core.policy.LockNotice
import jp.tasklock.core.policy.LockSelection
import jp.tasklock.core.policy.LockSelectionDiff
import jp.tasklock.core.template.TaskTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ロック対象の下書き。[selected] は確定後にロック対象となる集合、[labels] は表示名 */
data class LockDraft(val selected: Set<String>, val labels: Map<String, String>)

data class PermissionState(val accessibilityEnabled: Boolean = false, val usageAccessGranted: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as TaskLockApp).container
    private val repository = container.repository

    val today: StateFlow<TodayState?> = repository.todayState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** onResume で呼ぶ。設定画面から戻った時の権限状態と日付の切り替わりを反映する */
    fun refresh() {
        repository.refreshDay()
        _permissions.value = PermissionState(
            accessibilityEnabled = AccessibilityStatus.isEnabled(getApplication()),
            usageAccessGranted = container.usageStats.hasPermission(),
        )
    }

    fun consumeMessage() {
        _message.value = null
    }

    suspend fun launchableApps(includeDefaultSms: Boolean = false): List<AppInfo> =
        withContext(Dispatchers.IO) { container.installedApps.launchableApps(includeDefaultSms) }

    /** 保存できた場合のみ [onSaved] を呼ぶ。拒否された場合は理由をメッセージで表示する */
    fun addTask(template: TaskTemplate, title: String, target: Int, targetPackage: String?, onSaved: () -> Unit) {
        viewModelScope.launch {
            val result = repository.addTask(template, title, target, targetPackage)
            if (result == ChangeResult.Ok) onSaved() else report(result)
        }
    }

    fun deactivateTask(id: Long) {
        viewModelScope.launch { report(repository.deactivateTask(id)) }
    }

    // ---- ロック対象の選択（DESIGN.md §9.6-6） ----
    // チェックは下書き（メモリ上）だけを変え、DB とロック状態には影響しない。
    // DB に書き込むのは commitRemovalOnly（外すだけの確定）と applyConfirmedLockSelection（確認画面の確定）だけ。

    private val _lockDraft = MutableStateFlow<LockDraft?>(null)
    val lockDraft: StateFlow<LockDraft?> = _lockDraft.asStateFlow()

    private fun savedLockedApps(): Map<String, String> =
        today.value?.lockedApps?.associate { it.packageName to it.label }.orEmpty()

    /**
     * ホームからロック対象の画面を開くときに呼ぶ。保存済みの状態から新しい下書きを始める。
     * 保存済みの状態が未ロードのときは始めない（未ロードを「0件」と扱うと、差分が「全部外す」になるため）
     */
    fun beginLockSelection() {
        val loaded = today.value ?: return
        val saved = loaded.lockedApps.associate { it.packageName to it.label }
        _lockDraft.value = LockDraft(selected = saved.keys, labels = saved)
    }

    /** 下書きのチェックを変える。DB には書き込まない */
    fun setDraftLocked(app: AppInfo, locked: Boolean) {
        val draft = _lockDraft.value ?: return
        _lockDraft.value = draft.copy(
            selected = if (locked) draft.selected + app.packageName else draft.selected - app.packageName,
            labels = draft.labels + (app.packageName to app.label),
        )
    }

    fun lockSelectionDiff(): LockSelectionDiff =
        LockSelection.diff(savedLockedApps().keys, _lockDraft.value?.selected ?: savedLockedApps().keys)

    fun discardLockSelection() {
        _lockDraft.value = null
    }

    suspend fun previewLockNotice(diff: LockSelectionDiff): LockNotice = repository.previewLockNotice(diff)

    /** 確認画面の「重要なアプリに関する警告」の対象（§9.6-3）。追加するアプリだけを判定し、DB には触れない */
    suspend fun importantApps(diff: LockSelectionDiff): Set<String> = withContext(Dispatchers.IO) {
        val apps = container.installedApps
        ImportantApps.detect(diff.added, apps.smsHandlerPackages(), apps.geoHandlerPackages())
    }

    /**
     * ロック対象の画面の「確定」で、外すだけの変更を反映する。
     * 追加を含む変更はここでは反映せず（確認画面を経由させる）、何も書き込まずに false を返す。
     */
    fun commitRemovalOnly(onApplied: () -> Unit): Boolean {
        val diff = lockSelectionDiff()
        if (diff.needsConfirmation) return false
        applyDiff(diff, onApplied)
        return true
    }

    /** 確認画面の「確定」。ここで初めて追加を DB とロック状態に反映する。拒否されたときは [onRejected] を呼ぶ */
    fun applyConfirmedLockSelection(onApplied: () -> Unit, onRejected: () -> Unit = {}) =
        applyDiff(lockSelectionDiff(), onApplied, onRejected)

    private fun applyDiff(diff: LockSelectionDiff, onApplied: () -> Unit, onRejected: () -> Unit = {}) {
        if (diff.isEmpty) {
            onApplied()
            discardLockSelection()
            return
        }
        val labels = _lockDraft.value?.labels.orEmpty()
        viewModelScope.launch {
            val result = repository.applyLockSelection(
                added = diff.added.associateWith { labels[it] ?: it },
                removed = diff.removed,
            )
            // 拒否されたときは下書きを残し、理由を表示する
            if (result == ChangeResult.Ok) {
                // 先に画面を閉じてから下書きを消す（ロック対象の画面が残ったまま下書きが作り直されないように）
                onApplied()
                discardLockSelection()
            } else {
                report(result)
                onRejected()
            }
        }
    }

    private fun report(result: ChangeResult) {
        if (result is ChangeResult.Rejected) _message.value = result.reason.message
    }

    suspend fun getTask(id: Long): Task? = repository.getTask(id)

    suspend fun previousEndPage(taskId: Long): Int? = repository.previousEndPage(taskId)

    suspend fun measuredMinutes(task: Task): Long =
        withContext(Dispatchers.IO) { repository.measureAppUsageToday(task).toMinutes() }

    suspend fun complete(task: Task, input: CompletionInput): CompletionResult =
        withContext(Dispatchers.IO) { repository.complete(task, input) }
}
