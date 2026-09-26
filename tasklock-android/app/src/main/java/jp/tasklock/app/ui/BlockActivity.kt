package jp.tasklock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.ui.theme.TaskLockTheme

/**
 * ロック中のアプリの上に表示する画面。
 * 「戻る」で元のアプリに戻れないよう、戻る操作はホーム画面への移動に置き換える。
 */
class BlockActivity : ComponentActivity() {

    private var blockedLabel by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLabel(intent)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )
        setContent {
            TaskLockTheme {
                BlockScreen(
                    appLabel = blockedLabel,
                    onOpenTasks = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                    onGoHome = ::goHome,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateLabel(intent)
    }

    private fun updateLabel(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        blockedLabel = (application as TaskLockApp).container.installedApps.labelOf(pkg)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}

@Composable
private fun BlockScreen(appLabel: String, onOpenTasks: () -> Unit, onGoHome: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔒", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "${appLabel}は今ロック中です",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "今日のタスクを1つ終えると、今日いっぱい開けるようになります。",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onOpenTasks, modifier = Modifier.fillMaxWidth()) { Text("今日のタスクへ") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) { Text("ホームに戻る") }
        }
    }
}
