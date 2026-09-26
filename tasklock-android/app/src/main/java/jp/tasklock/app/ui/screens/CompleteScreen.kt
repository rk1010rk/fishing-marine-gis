package jp.tasklock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.tasklock.app.data.CompletionInput
import jp.tasklock.app.data.CompletionResult
import jp.tasklock.app.ui.MainViewModel
import jp.tasklock.core.model.Task
import jp.tasklock.core.model.TaskCategory
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.model.VerificationStatus
import jp.tasklock.core.verify.ReadingCheck
import jp.tasklock.core.verify.Verifier
import kotlinx.coroutines.launch

/**
 * タスク完了: 内容確認 → 完了申告（必要なら客観データで確認）→ 結果表示 を1画面で完結させる。
 */
@Composable
fun CompleteScreen(
    vm: MainViewModel,
    taskId: Long,
    usageAccessGranted: Boolean,
    onOpenUsageSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var task by remember { mutableStateOf<Task?>(null) }
    var result by remember { mutableStateOf<CompletionResult?>(null) }
    LaunchedEffect(taskId) { task = vm.getTask(taskId) }
    val t = task ?: return

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(t.title, style = MaterialTheme.typography.headlineSmall)
        Text("目標: ${t.targetValue}${t.unit.label}", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))

        when (val r = result) {
            is CompletionResult.Saved -> ResultView(t, r, onDone)
            else -> {
                if (r is CompletionResult.Rejected) {
                    Text(r.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    t.verificationPolicy == VerificationPolicy.APP_USAGE ->
                        AppUsageForm(vm, t, usageAccessGranted, onOpenUsageSettings) { result = it }
                    t.category == TaskCategory.READING -> ReadingForm(vm, t) { result = it }
                    else -> CountForm(vm, t) { result = it }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("戻る") }
            }
        }
    }
}

@Composable
private fun CountForm(vm: MainViewModel, task: Task, onResult: (CompletionResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(task.targetValue.toString()) }
    val n = value.toIntOrNull() ?: 0
    OutlinedTextField(
        value = value,
        onValueChange = { value = it.filter(Char::isDigit).take(5) },
        label = { Text("今日やった量（${task.unit.label}）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { scope.launch { onResult(vm.complete(task, CompletionInput(reportedValue = n))) } },
        enabled = n >= task.targetValue,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("完了した") }
}

@Composable
private fun ReadingForm(vm: MainViewModel, task: Task, onResult: (CompletionResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var previousEnd by remember { mutableStateOf<Int?>(null) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    LaunchedEffect(task.id) {
        previousEnd = vm.previousEndPage(task.id)
        start = ((previousEnd ?: 0) + 1).toString()
    }
    val s = start.toIntOrNull()
    val e = end.toIntOrNull()
    val pages = if (s != null && e != null && e >= s) e - s + 1 else 0
    val check = if (s != null && e != null) Verifier.checkReading(previousEnd, s, e, pages) else null

    previousEnd?.let { Text("前回は${it}ページまで読みました") }
    OutlinedTextField(
        value = start,
        onValueChange = { start = it.filter(Char::isDigit).take(5) },
        label = { Text("読み始めたページ") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = end,
        onValueChange = { end = it.filter(Char::isDigit).take(5) },
        label = { Text("読み終えたページ") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("今日読んだページ: ${pages}ページ / 目標 ${task.targetValue}ページ")
    if (check is ReadingCheck.Mismatch) Text(check.message, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            scope.launch {
                onResult(vm.complete(task, CompletionInput(reportedValue = pages, startPage = s, endPage = e)))
            }
        },
        enabled = check == ReadingCheck.Ok && pages >= task.targetValue,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("完了した") }
}

@Composable
private fun AppUsageForm(
    vm: MainViewModel,
    task: Task,
    usageAccessGranted: Boolean,
    onOpenUsageSettings: () -> Unit,
    onResult: (CompletionResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var measured by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(task.id, usageAccessGranted) {
        if (usageAccessGranted) measured = vm.measuredMinutes(task)
    }
    if (!usageAccessGranted) {
        Text("学習アプリの利用時間を確認するには「使用状況へのアクセス」の許可が必要です。")
        Button(onClick = onOpenUsageSettings, modifier = Modifier.fillMaxWidth()) { Text("許可する") }
        return
    }
    val m = measured
    Text(if (m == null) "計測中…" else "今日の利用時間: ${m}分 / 目標 ${task.targetValue}分")
    Text(
        "学習アプリを画面に表示していた時間です。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            scope.launch {
                val r = vm.complete(task, CompletionInput(reportedValue = task.targetValue))
                if (r is CompletionResult.Saved) measured = r.measuredMinutes
                onResult(r)
            }
        },
        enabled = m != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("利用時間を確認して完了") }
}

@Composable
private fun ResultView(task: Task, result: CompletionResult.Saved, onDone: () -> Unit) {
    when {
        result.unlocked -> {
            Text("🎉 おつかれさまでした", style = MaterialTheme.typography.headlineSmall)
            val how = if (result.status == VerificationStatus.VERIFIED) "利用時間から自動で確認しました。" else "記録しました。"
            Text("$how 今日はロックしたアプリを開けます。")
        }
        task.verificationPolicy == VerificationPolicy.APP_USAGE -> {
            val remaining = task.targetValue - (result.measuredMinutes ?: 0)
            Text("あと${remaining}分です", style = MaterialTheme.typography.headlineSmall)
            Text("学習アプリで続きをやってから、もう一度確認してください。")
        }
        else -> Text("記録しました。")
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("ホームへ") }
}
