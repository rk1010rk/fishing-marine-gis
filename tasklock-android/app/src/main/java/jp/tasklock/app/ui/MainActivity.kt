package jp.tasklock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.platform.AccessibilityStatus
import jp.tasklock.app.ui.screens.AccessibilityDisclosureScreen
import jp.tasklock.app.ui.screens.CompleteScreen
import jp.tasklock.app.ui.screens.HomeScreen
import jp.tasklock.app.ui.screens.LockConfirmScreen
import jp.tasklock.app.ui.screens.LockedAppsScreen
import jp.tasklock.app.ui.screens.TaskSetupScreen
import jp.tasklock.app.ui.theme.TaskLockTheme

/** 画面遷移。MVPでは画面数が少ないためナビゲーションライブラリは使わない */
private sealed interface Screen {
    data object Home : Screen

    /** [thenLockedApps]: 有効なタスクが0件でロック対象の画面を開こうとした場合。保存後にロック対象の画面へ進む */
    data class TaskSetup(val thenLockedApps: Boolean = false) : Screen
    data class Complete(val taskId: Long) : Screen
    data object LockedApps : Screen

    /** ロック対象の追加を反映する直前の確認画面（DESIGN.md §9.6-6） */
    data object LockConfirm : Screen
    data object AccessibilityDisclosure : Screen
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TaskLockTheme { MainContent(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    @Composable
    private fun MainContent(vm: MainViewModel) {
        // プロセス再生成時は Home に戻れば十分なので Saver は用意しない
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        val today by vm.today.collectAsStateWithLifecycle()
        val permissions by vm.permissions.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val lockDraft by vm.lockDraft.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        var disclosureReturnTo by rememberSaveable { mutableStateOf(false) }

        // 緊急解除中の「前のロック対象で再開」の候補の数（表示用。押したときに読み直す）
        var restoreCount by remember { mutableStateOf(0) }
        val emergencyStopId = today?.emergencyStop?.id
        val studyPackages = today?.studyPackages
        LaunchedEffect(emergencyStopId, studyPackages) {
            restoreCount = if (emergencyStopId == null) 0 else vm.restoreCandidates().size
        }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                vm.consumeMessage()
            }
        }
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

        // 初回利用時はタスクを先に設定する（DESIGN.md §9.6-6 決定事項2）。タスクの設定だけではロックは始まらない
        fun openLockedApps() {
            if (today?.tasks?.isEmpty() == true) {
                screen = Screen.TaskSetup(thenLockedApps = true)
            } else {
                vm.beginLockSelection()
                screen = Screen.LockedApps
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            val modifier = Modifier.padding(padding)
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    today = today,
                    permissions = permissions,
                    onAddTask = { screen = Screen.TaskSetup() },
                    onCompleteTask = { screen = Screen.Complete(it) },
                    onDeleteTask = vm::deactivateTask,
                    onLockedApps = { openLockedApps() },
                    onEnableBlocking = { screen = Screen.AccessibilityDisclosure },
                    restoreCount = restoreCount,
                    // 確認画面へ直接進む（DESIGN.md §9.6-2「再開」）。戻るとロック対象の画面で下書きを直せる
                    onRestoreLock = { vm.beginRestoreSelection(onReady = { screen = Screen.LockConfirm }) },
                    modifier = modifier,
                )
                is Screen.TaskSetup -> TaskSetupScreen(
                    vm = vm,
                    today = today,
                    usageAccessGranted = permissions.usageAccessGranted,
                    onOpenUsageSettings = { startActivity(appContainer().usageStats.settingsIntent()) },
                    onDone = {
                        if (s.thenLockedApps) {
                            vm.beginLockSelection()
                            screen = Screen.LockedApps
                        } else {
                            screen = Screen.Home
                        }
                    },
                    intro = if (s.thenLockedApps) {
                        "ロックするアプリを選ぶ前に、毎日やるタスクを1つ決めましょう。タスクを決めただけではロックは始まりません。"
                    } else {
                        null
                    },
                    modifier = modifier,
                )
                is Screen.Complete -> CompleteScreen(
                    vm = vm,
                    taskId = s.taskId,
                    usageAccessGranted = permissions.usageAccessGranted,
                    onOpenUsageSettings = { startActivity(appContainer().usageStats.settingsIntent()) },
                    onDone = { screen = Screen.Home },
                    modifier = modifier,
                )
                Screen.LockedApps -> LockedAppsScreen(
                    vm = vm,
                    draft = lockDraft,
                    lockedPackages = today?.lockedApps?.map { it.packageName }?.toSet().orEmpty(),
                    studyPackages = today?.studyPackages.orEmpty(),
                    locked = today?.locked == true,
                    emergencyStopped = today?.emergencyStop != null,
                    accessibilityEnabled = permissions.accessibilityEnabled,
                    onEnableBlocking = {
                        disclosureReturnTo = true
                        screen = Screen.AccessibilityDisclosure
                    },
                    onNeedConfirmation = { screen = Screen.LockConfirm },
                    onDone = { screen = Screen.Home },
                    modifier = modifier,
                )
                Screen.LockConfirm -> LockConfirmScreen(
                    vm = vm,
                    draft = lockDraft,
                    savedLabels = today?.lockedApps?.associate { it.packageName to it.label }.orEmpty(),
                    resuming = today?.emergencyStop != null,
                    onBack = { screen = Screen.LockedApps },
                    onApplied = { screen = Screen.Home },
                    modifier = modifier,
                )
                Screen.AccessibilityDisclosure -> AccessibilityDisclosureScreen(
                    onAgree = {
                        startActivity(AccessibilityStatus.settingsIntent())
                        screen = if (disclosureReturnTo) Screen.LockedApps else Screen.Home
                        disclosureReturnTo = false
                    },
                    onDecline = {
                        screen = if (disclosureReturnTo) Screen.LockedApps else Screen.Home
                        disclosureReturnTo = false
                    },
                    modifier = modifier,
                )
            }
        }
    }

    private fun appContainer() = (application as TaskLockApp).container
}
