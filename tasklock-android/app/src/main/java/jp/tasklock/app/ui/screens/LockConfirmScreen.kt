package jp.tasklock.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.tasklock.app.ui.LockDraft
import jp.tasklock.app.ui.MainViewModel
import jp.tasklock.core.policy.LockNotice
import jp.tasklock.core.policy.LockSelection

/**
 * ロック対象の追加を反映する直前の確認画面（DESIGN.md §9.6-6）。
 * 最下部の「確定」で初めて DB とロック状態に反映する。戻るとロック対象の画面に戻り、下書きは残る。
 * 表示は ①ロック対象の変更 ②重要なアプリに関する警告 ③ロックに関する注意 の3欄（該当するものだけ）。
 */
@Composable
fun LockConfirmScreen(
    vm: MainViewModel,
    draft: LockDraft?,
    savedLabels: Map<String, String>,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val diff = LockSelection.diff(savedLabels.keys, draft?.selected ?: savedLabels.keys)
    val labels = savedLabels + draft?.labels.orEmpty()
    fun labelsOf(packages: Set<String>) = packages.map { labels[it] ?: it }.sorted()

    var notice by remember { mutableStateOf<LockNotice?>(null) }
    LaunchedEffect(diff) { notice = vm.previewLockNotice(diff) }
    // 重要なアプリの判定（smsto:・geo:・固定リスト）は項目3で実装する。項目6では欄だけを用意する
    val importantApps = emptyList<String>()
    var applying by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("変更内容の確認", style = MaterialTheme.typography.headlineSmall)

            Section(title = "ロック対象の変更") {
                if (diff.added.isNotEmpty()) Text("追加するアプリ: " + labelsOf(diff.added).joinToString("、"))
                if (diff.removed.isNotEmpty()) Text("外すアプリ: " + labelsOf(diff.removed).joinToString("、"))
            }

            if (importantApps.isNotEmpty()) {
                Section(title = "重要なアプリに関する警告") {
                    importantApps.forEach { Text("$it: このアプリは連絡・移動に使用される可能性があります") }
                }
            }

            when (notice) {
                LockNotice.ADDING_WHILE_LOCKED -> Section(title = "ロックに関する注意", warning = true) {
                    Text("追加したアプリは、今日のタスクを達成するまで外せません。")
                }
                LockNotice.STARTS_LOCK -> Section(title = "ロックに関する注意", warning = true) {
                    Text("確定すると、すぐにロックが始まります。今日のタスクを達成するまで、追加したアプリは開けず、ロック対象から外すこともできません。")
                }
                LockNotice.NONE, null -> Unit
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    applying = true
                    // 拒否されたときは理由がメッセージで表示され、下書きは残る
                    vm.applyConfirmedLockSelection(onApplied = onApplied, onRejected = { applying = false })
                },
                enabled = draft != null && !applying && notice != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("確定") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("戻って修正する") }
        }
    }
}

@Composable
private fun Section(title: String, warning: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (warning) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
