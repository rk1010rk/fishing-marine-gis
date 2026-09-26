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
import jp.tasklock.app.ui.screens.LockedAppsScreen
import jp.tasklock.app.ui.screens.TaskSetupScreen
import jp.tasklock.app.ui.theme.TaskLockTheme

/** 画面遷移。MVPでは画面数が少ないためナビゲーションライブラリは使わない */
private sealed interface Screen {
    data object Home : Screen
    data object TaskSetup : Screen
    data class Complete(val taskId: Long) : Screen
    data object LockedApps : Screen
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
        val snackbar = remember { SnackbarHostState() }
        var disclosureReturnTo by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                vm.consumeMessage()
            }
        }
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            val modifier = Modifier.padding(padding)
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    today = today,
                    permissions = permissions,
                    onAddTask = { screen = Screen.TaskSetup },
                    onCompleteTask = { screen = Screen.Complete(it) },
                    onDeleteTask = vm::deactivateTask,
                    onLockedApps = { screen = Screen.LockedApps },
                    onEnableBlocking = { screen = Screen.AccessibilityDisclosure },
                    modifier = modifier,
                )
                Screen.TaskSetup -> TaskSetupScreen(
                    vm = vm,
                    usageAccessGranted = permissions.usageAccessGranted,
                    onOpenUsageSettings = { startActivity(appContainer().usageStats.settingsIntent()) },
                    onDone = { screen = Screen.Home },
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
                    lockedPackages = today?.lockedApps?.map { it.packageName }?.toSet().orEmpty(),
                    accessibilityEnabled = permissions.accessibilityEnabled,
                    onEnableBlocking = {
                        disclosureReturnTo = true
                        screen = Screen.AccessibilityDisclosure
                    },
                    onDone = { screen = Screen.Home },
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
