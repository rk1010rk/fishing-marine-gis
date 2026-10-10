package jp.tasklock.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import jp.tasklock.app.ui.LockDraft
import jp.tasklock.app.ui.MainViewModel
import jp.tasklock.core.policy.LockSelection

/**
 * ロック対象アプリの選択（DESIGN.md §9.6-6）。
 * チェックは下書きで、DB とロック状態には影響しない。下部の「確定」で差分を判定し、
 * 外すだけならその場で反映し、追加を含むなら確認画面へ進む（この時点では何も書き込まない）。
 * ロック中は保存済みの対象を外せない。学習アプリに設定中のアプリは選べない（最終判定は Repository 側）。
 * 既定の SMS アプリは候補に出さないが、登録済みの場合は DB の行を残したまま「除外中」と表示する（ブロックはされない）
 * 登録済みでも候補の一覧に無い行（既定の SMS アプリ以外の除外中のアプリ、アンインストール済み、ランチャーに表示されない
 * アプリ）は、一覧の後ろに理由を付けて表示し、ほかの登録済みの行と同じく、ロック中でなければ外せる（DESIGN.md §9.6-7）。
 * アンインストール済みとランチャーに表示されないアプリは、パッケージの可視性（<queries>）の制約で区別できないため、同じ表示にする
 */
@Composable
fun LockedAppsScreen(
    vm: MainViewModel,
    draft: LockDraft?,
    lockedPackages: Set<String>,
    /** 登録済みの行の表示名（locked_apps の label）。候補の一覧に無い行の表示に使う */
    lockedLabels: Map<String, String>,
    studyPackages: Set<String>,
    locked: Boolean,
    /** 緊急解除中。確定のボタンを「ロックを再開する」と表示する（DESIGN.md §9.6-2「v3 の DB 設計」） */
    emergencyStopped: Boolean,
    accessibilityEnabled: Boolean,
    onEnableBlocking: () -> Unit,
    onNeedConfirmation: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var exempt by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        // 除外一覧を取得してから候補一覧を取得する
        exempt = vm.currentExemptPackages()
        apps = vm.launchableApps(includeDefaultSms = true)
    }
    // ホーム以外の経路で開かれた場合も、保存済みの状態から下書きを始める
    LaunchedEffect(draft == null) { if (draft == null) vm.beginLockSelection() }

    val selected = draft?.selected ?: lockedPackages
    val diff = LockSelection.diff(lockedPackages, selected)
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler {
        if (diff.isEmpty) {
            vm.discardLockSelection()
            onDone()
        } else {
            confirmDiscard = true
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("変更を破棄しますか？") },
            text = { Text("確定していない変更は保存されません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.discardLockSelection()
                    onDone()
                }) { Text("破棄する") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("編集を続ける") } },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("ロックするアプリ", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "タスクを終えるまで開けなくするアプリを選び、「確定」を押してください。確定するまで変更は保存されません。" +
                        "ロック中は、登録済みのアプリを外せません。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (emergencyStopped) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "緊急解除中です。ロック対象を選んで「ロックを再開する」を押すと、ロックが再開します。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
            if (list == null || draft == null) {
                item { Text("読み込み中…") }
            } else {
                val visible = list.filter { !it.isDefaultSms || it.packageName in lockedPackages }
                val listed = list.map { it.packageName }.toSet()
                // 登録済みだが候補の一覧に無い行。下書きには保存済みの全行が入っているので、ここに出しても確定の差分は変わらない
                val notListed = lockedPackages.filter { it !in listed }
                    .map { AppInfo(it, lockedLabels[it] ?: it) }
                    .sortedWith(compareBy({ it.label }, { it.packageName }))
                items(visible + notListed, key = { it.packageName }) { app ->
                    val saved = app.packageName in lockedPackages
                    val checked = app.packageName in selected
                    val isStudyApp = app.packageName in studyPackages
                    val enabled = when {
                        saved && checked -> !locked // ロック中は保存済みの対象を外せない
                        checked -> true // 下書きで追加したものは確定まで自由に外せる
                        else -> !isStudyApp
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.setDraftLocked(app, !checked) },
                    ) {
                        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { vm.setDraftLocked(app, it) })
                        Column {
                            Text(app.label)
                            if (checked && !saved) {
                                Text("追加予定（未確定）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (!checked && saved) {
                                Text("外す予定（未確定）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (isStudyApp) Text("学習アプリに設定中", style = MaterialTheme.typography.bodySmall)
                            if (app.isDefaultSms) Text("除外中（既定のSMSアプリ）", style = MaterialTheme.typography.bodySmall)
                            if (app.packageName !in listed) {
                                // 除外中なら canLockApp が追加を拒否し、候補にも出ないため、外すと除外中は再登録できない
                                val (reason, detail) = if (app.packageName in exempt) {
                                    "除外中（既定のホーム・電話アプリなど）" to "ブロックされません。外すと、除外中は再登録できません。"
                                } else {
                                    // ブロックされるかは区別できないため書かない（アンインストール済みなら開けず、隠れたアプリなら開けばブロックされる）
                                    "一覧に見つかりません" to "アンインストール済み、またはランチャーに表示されないアプリです。"
                                }
                                Text(reason, style = MaterialTheme.typography.bodySmall)
                                Text(detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp)) {
            if (!diff.isEmpty) {
                Text(
                    "未確定の変更: 追加 ${diff.added.size}件・外す ${diff.removed.size}件",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    // 追加を含む場合は確認画面へ進むだけで、DB にもロック状態にも反映しない
                    if (!vm.commitRemovalOnly(onApplied = onDone)) onNeedConfirmation()
                },
                enabled = draft != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (emergencyStopped) "ロックを再開する" else "確定") }
        }
    }
}
