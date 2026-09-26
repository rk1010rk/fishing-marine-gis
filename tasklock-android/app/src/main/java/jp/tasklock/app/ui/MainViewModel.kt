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
import jp.tasklock.core.template.TaskTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    suspend fun launchableApps(): List<AppInfo> = withContext(Dispatchers.IO) { container.installedApps.launchableApps() }

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

    fun setLocked(app: AppInfo, locked: Boolean) {
        viewModelScope.launch {
            report(
                if (locked) repository.addLockedApp(app.packageName, app.label)
                else repository.removeLockedApp(app.packageName),
            )
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
