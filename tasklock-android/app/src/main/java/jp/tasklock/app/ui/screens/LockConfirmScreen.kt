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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.tasklock.app.ui.LockDraft
import jp.tasklock.app.ui.MainViewModel
import jp.tasklock.core.policy.EmergencyStopPolicy
import jp.tasklock.core.policy.LockNotice
import jp.tasklock.core.policy.LockSelection

/**
 * ロック対象の追加を反映する直前の確認画面（DESIGN.md §9.6-6）。
 * 最下部の「確定」で初めて DB とロック状態に反映する。戻るとロック対象の画面に戻り、下書きは残る。
 * 表示は ①ロック対象の変更 ②重要なアプリに関する警告 ③ロックに関する注意 の3欄（該当するものだけ）。
 * 緊急解除中（[resuming]）は、確定でロックが再開することと理由の入力欄（任意）を出し、確定のボタンを
 * 「ロックを再開する」と表示する。再開するかどうかは Repository が DB の状態で判定する（DESIGN.md §9.6-2）。
 */
@Composable
fun LockConfirmScreen(
    vm: MainViewModel,
    draft: LockDraft?,
    savedLabels: Map<String, String>,
    resuming: Boolean,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val diff = LockSelection.diff(savedLabels.keys, draft?.selected ?: savedLabels.keys)
    val labels = savedLabels + draft?.labels.orEmpty()
    fun labelsOf(packages: Set<String>) = packages.map { labels[it] ?: it }.sorted()

    // 判定結果は diff ごとに持ち直す（差分が変わったとき、古い判定結果が新しい差分と混ざらないように）
    var notice by remember(diff) { mutableStateOf<LockNotice?>(null) }
    var importantApps by remember(diff) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(diff) {
        notice = vm.previewLockNotice(diff)
        // 重要なアプリの判定（§9.6-3: smsto:・geo:・固定リスト）。追加するアプリだけが対象
        importantApps = labelsOf(vm.importantApps(diff))
    }
    var applying by remember { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }

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

            val important = importantApps.orEmpty()
            if (important.isNotEmpty()) {
                Section(title = "重要なアプリに関する警告") {
                    important.forEach { Text("$it: このアプリは連絡・移動に使用される可能性があります") }
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

            if (resuming && diff.added.isNotEmpty()) {
                Section(title = "ロックの再開") {
                    Text("確定すると、ロックが再開します。")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        // 上限はコードポイントで数える（保存時も Repository で正規化する）
                        onValueChange = { reason = it.limitCodePoints(EmergencyStopPolicy.REASON_MAX_LENGTH) },
                        label = { Text("緊急解除した理由（任意）") },
                        supportingText = { Text("${EmergencyStopPolicy.REASON_MAX_LENGTH}文字まで。入力しなくても再開できます") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    applying = true
                    // 拒否されたときは理由がメッセージで表示され、下書きは残る
                    vm.applyConfirmedLockSelection(
                        onApplied = onApplied,
                        onRejected = { applying = false },
                        reason = if (resuming) reason else null,
                    )
                },
                enabled = draft != null && !applying && notice != null && importantApps != null, // 判定が終わるまで確定できない
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (resuming) "ロックを再開する" else "確定") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("戻って修正する") }
        }
    }
}

/** 先頭から [max] コードポイントまでにする（サロゲートペアを途中で切らない） */
private fun String.limitCodePoints(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

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
