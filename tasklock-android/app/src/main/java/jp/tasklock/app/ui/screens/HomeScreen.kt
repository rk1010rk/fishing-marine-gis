package jp.tasklock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.tasklock.app.data.TaskProgress
import jp.tasklock.app.data.TodayState
import jp.tasklock.app.ui.PermissionState
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import java.time.format.DateTimeFormatter

/**
 * ホーム: 今日のタスク / 達成状態 / ロック状態 / 次に必要な操作 を1画面にまとめる。
 */
@Composable
fun HomeScreen(
    today: TodayState?,
    permissions: PermissionState,
    onAddTask: () -> Unit,
    onCompleteTask: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onLockedApps: () -> Unit,
    onEnableBlocking: () -> Unit,
    /** 緊急解除中の「前のロック対象で再開」の候補の数。0 ならボタンを出さない */
    restoreCount: Int,
    onRestoreLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (today == null) return
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                today.day.format(DateTimeFormatter.ofPattern("M月d日")) + "のタスク",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item { LockStatusCard(today, permissions, onLockedApps, onEnableBlocking, restoreCount, onRestoreLock) }

        if (today.tasks.isEmpty()) {
            item {
                Text("まずは毎日やるタスクを1つ決めましょう", style = MaterialTheme.typography.bodyLarge)
            }
        }
        // ロック中は解除条件を変えられない（タスクが0件のときの追加だけは可能）
        val canEditTasks = !today.locked
        items(today.tasks, key = { it.task.id }) { progress ->
            TaskCard(
                progress,
                canDelete = canEditTasks,
                onComplete = { onCompleteTask(progress.task.id) },
                onDelete = { onDeleteTask(progress.task.id) },
            )
        }
        item {
            val canAdd = canEditTasks || today.tasks.isEmpty()
            OutlinedButton(onClick = onAddTask, enabled = canAdd, modifier = Modifier.fillMaxWidth()) { Text("タスクを追加") }
            if (!canAdd) {
                Text(
                    "ロック中はタスクの追加・削除ができません。今日のタスクを終えると変更できます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LockStatusCard(
    today: TodayState,
    permissions: PermissionState,
    onLockedApps: () -> Unit,
    onEnableBlocking: () -> Unit,
    restoreCount: Int,
    onRestoreLock: () -> Unit,
) {
    val unlocked = today.grant != null
    val noApps = today.lockedApps.isEmpty()
    // 一時解除中もロック中（タスクの追加・削除はできない）。ブロックだけが止まっている（DESIGN.md §9.6-2）
    val temporaryMinutes = today.temporaryRemainingMinutes
    val container = when {
        !permissions.accessibilityEnabled || noApps -> MaterialTheme.colorScheme.surfaceVariant
        unlocked || temporaryMinutes != null -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val (title, body) = when {
                !permissions.accessibilityEnabled ->
                    "ブロック機能がオフです" to "アプリをロックするには、ユーザー補助の設定でタスクロックをオンにしてください。"
                noApps -> "ロックするアプリが未設定です" to "SNSやゲームなど、タスクが終わるまで開けないようにするアプリを選びましょう。"
                unlocked -> "🔓 今日は解除済み" to "お疲れさまでした。${today.lockedApps.size}個のアプリを今日いっぱい使えます。"
                temporaryMinutes != null ->
                    "⏳ 一時解除中・残り${temporaryMinutes}分" to "期限が来ると、使用中でもロックに戻ります。"
                else -> "🔒 ロック中" to "タスクを1つ終えると、${today.lockedApps.size}個のアプリが開けるようになります。"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!permissions.accessibilityEnabled) {
                    Button(onClick = onEnableBlocking) { Text("オンにする") }
                }
                TextButton(onClick = onLockedApps) { Text(if (noApps) "アプリを選ぶ" else "ロック対象を見る") }
            }
            // 緊急解除中は「ロックするアプリが未設定です」のまま、前のロック対象での再開を出す（DESIGN.md §9.6-2）
            if (noApps && today.emergencyStop != null && restoreCount > 0) {
                Button(onClick = onRestoreLock, modifier = Modifier.fillMaxWidth()) {
                    Text("前のロック対象（${restoreCount}個）でロックを再開")
                }
            }
        }
    }
}

@Composable
private fun TaskCard(progress: TaskProgress, canDelete: Boolean, onComplete: () -> Unit, onDelete: () -> Unit) {
    val task = progress.task
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text("目標: ${task.targetValue}${task.unit.label}", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onDelete, enabled = canDelete) { Text("削除") }
            }
            Spacer(Modifier.height(8.dp))
            if (progress.completedToday) {
                // UIでは「自己申告」「自動確認」の2種類だけを見せる
                val label = if (progress.bestStatus == VerificationStatus.VERIFIED) "✅ 達成（自動で確認済み）" else "✅ 達成（記録済み）"
                Text(label, color = MaterialTheme.colorScheme.primary)
            } else {
                val cta = if (task.verificationPolicy == VerificationPolicy.APP_USAGE) "利用時間を確認する" else "完了を記録する"
                Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text(cta) }
            }
        }
    }
}
