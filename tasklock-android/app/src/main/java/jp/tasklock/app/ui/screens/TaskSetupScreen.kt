package jp.tasklock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.tasklock.app.data.TodayState
import jp.tasklock.app.platform.AppInfo
import jp.tasklock.app.ui.MainViewModel
import jp.tasklock.core.model.VerificationPolicy
import jp.tasklock.core.policy.ChangePolicy
import jp.tasklock.core.policy.ChangeRejection
import jp.tasklock.core.template.TaskTemplate
import jp.tasklock.core.template.TaskTemplates

/** テンプレートを選び、目標値（と学習アプリ）を決めるだけの1画面 */
@Composable
fun TaskSetupScreen(
    vm: MainViewModel,
    today: TodayState?,
    usageAccessGranted: Boolean,
    onOpenUsageSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<TaskTemplate?>(null) }
    val template = selected
    if (template == null) {
        TemplateList(onSelect = { selected = it }, modifier = modifier)
    } else {
        TaskForm(
            vm = vm,
            today = today,
            template = template,
            usageAccessGranted = usageAccessGranted,
            onOpenUsageSettings = onOpenUsageSettings,
            onBack = { selected = null },
            onSaved = onDone,
            modifier = modifier,
        )
    }
}

@Composable
private fun TemplateList(onSelect: (TaskTemplate) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("どんなタスクにしますか？", style = MaterialTheme.typography.headlineSmall) }
        items(TaskTemplates.MVP, key = { it.id }) { t ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(t) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TaskForm(
    vm: MainViewModel,
    today: TodayState?,
    template: TaskTemplate,
    usageAccessGranted: Boolean,
    onOpenUsageSettings: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier,
) {
    var title by remember(template) { mutableStateOf(template.title) }
    var target by remember(template) { mutableStateOf(template.defaultTarget.toString()) }
    var studyApp by remember(template) { mutableStateOf<AppInfo?>(null) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val needsApp = template.policy == VerificationPolicy.APP_USAGE

    val lockedPackages = today?.lockedApps?.map { it.packageName }?.toSet().orEmpty()
    // 自動確認タスクは、自己申告で解除できるタスクがすでにある場合だけ追加できる（計測不能での永久ロック防止）
    val needsSelfReportFirst = !ChangePolicy.isSelfReportable(template.requiredStatus) && today?.hasSelfReportTask != true

    // ロック対象のアプリは学習アプリの候補から外す（開けないため計測できない）
    LaunchedEffect(needsApp, lockedPackages) {
        if (needsApp) apps = vm.launchableApps().filter { it.packageName !in lockedPackages }
    }

    val targetValue = target.toIntOrNull()?.takeIf { it > 0 }
    val canSave = !needsSelfReportFirst && title.isNotBlank() && targetValue != null &&
        (!needsApp || (studyApp != null && usageAccessGranted))

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(template.title, style = MaterialTheme.typography.headlineSmall)
        if (needsSelfReportFirst) {
            Spacer(Modifier.height(8.dp))
            Text(ChangeRejection.NEEDS_SELF_REPORTED_TASK.message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(title, { title = it }, label = { Text("タスク名") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = target,
            onValueChange = { target = it.filter(Char::isDigit).take(5) },
            label = { Text("1日の目標（${template.unit.label}）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        if (needsApp) {
            Spacer(Modifier.height(16.dp))
            Text("使う学習アプリ", style = MaterialTheme.typography.titleMedium)
            Text(
                "このアプリを画面に表示していた時間を自動で計測します。表示していた時間であり、勉強の中身までは確認できません。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!usageAccessGranted) {
                Spacer(Modifier.height(8.dp))
                Text("計測には「使用状況へのアクセス」の許可が必要です。計測結果は端末の外へ送信しません。")
                Button(onClick = onOpenUsageSettings) { Text("許可する") }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { studyApp = app },
                    ) {
                        RadioButton(selected = studyApp == app, onClick = { studyApp = app })
                        Text(app.label)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                vm.addTask(template, title.trim(), targetValue ?: return@Button, studyApp?.packageName, onSaved)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("このタスクにする") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("テンプレートを選び直す") }
    }
}
