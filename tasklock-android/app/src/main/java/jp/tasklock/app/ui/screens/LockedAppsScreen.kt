package jp.tasklock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.tasklock.app.platform.AppInfo
import jp.tasklock.app.ui.MainViewModel

/**
 * ロック対象アプリの選択。
 * ロック中は「外す」操作、学習アプリに設定中のアプリの選択はできない（最終判定は Repository 側）
 * 既定の SMS アプリは候補に出さないが、登録済みの場合は DB の行を残したまま「除外中」と表示する（ブロックはされない）
 */
@Composable
fun LockedAppsScreen(
    vm: MainViewModel,
    lockedPackages: Set<String>,
    studyPackages: Set<String>,
    locked: Boolean,
    accessibilityEnabled: Boolean,
    onEnableBlocking: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(Unit) { apps = vm.launchableApps(includeDefaultSms = true) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ロックするアプリ", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("完了") }
            }
            Text(
                "タスクを終えるまで開けなくするアプリを選んでください。ロック中は対象から外せません。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!accessibilityEnabled) {
                Spacer(Modifier.height(8.dp))
                Column {
                    Text("ブロック機能がオフのため、今はロックされません。", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onEnableBlocking) { Text("ブロック機能をオンにする") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val list = apps
        if (list == null) {
            item { Text("読み込み中…") }
        } else {
            val visible = list.filter { !it.isDefaultSms || it.packageName in lockedPackages }
            items(visible, key = { it.packageName }) { app ->
                val checked = app.packageName in lockedPackages
                val isStudyApp = app.packageName in studyPackages
                val enabled = if (checked) !locked else !isStudyApp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.setLocked(app, !checked) },
                ) {
                    Checkbox(checked = checked, enabled = enabled, onCheckedChange = { vm.setLocked(app, it) })
                    Column {
                        Text(app.label)
                        if (isStudyApp) Text("学習アプリに設定中", style = MaterialTheme.typography.bodySmall)
                        if (app.isDefaultSms) Text("除外中（既定のSMSアプリ）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
