package jp.tasklock.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.data.TemporaryUnlockStatus
import jp.tasklock.app.ui.theme.TaskLockTheme
import jp.tasklock.core.policy.TemporaryUnlockDecision
import jp.tasklock.core.policy.TemporaryUnlockPolicy
import jp.tasklock.core.policy.TemporaryUnlockRejection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ロック中のアプリの上に表示する画面。
 * 「戻る」で元のアプリに戻れないよう、戻る操作はホーム画面への移動に置き換える。
 *
 * 一時解除（DESIGN.md §9.6-2）の入口はこの画面だけ。表示は案内で、開始できるかどうかは
 * Repository がトランザクション内で判定し直す。
 */
class BlockActivity : ComponentActivity() {

    private val repository get() = (application as TaskLockApp).container.repository

    private var blockedPackage by mutableStateOf("")
    private var blockedLabel by mutableStateOf("")
    private var confirming by mutableStateOf(false)
    private var status by mutableStateOf<TemporaryUnlockStatus?>(null)
    private var starting by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)

    /**
     * 確認画面の待ち時間を数え始めた時刻（elapsedRealtime）。画面を離れたら（onStop）null にして破棄し、
     * 戻ったら（onStart）その時刻から数え直す。null の間は「開始する」を押せない
     */
    private var waitStartedAt by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateTarget(intent)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        starting -> Unit // 開始処理中は画面を閉じない（記録とアプリの起動の途中で止めない）
                        confirming -> confirming = false // 確認をやめるだけで、DB には何も書かない
                        else -> goHome()
                    }
                }
            },
        )
        setContent {
            TaskLockTheme {
                BlockScreen(
                    appLabel = blockedLabel,
                    status = status,
                    confirming = confirming,
                    starting = starting,
                    notice = notice,
                    waitStartedAt = waitStartedAt,
                    onOpenTasks = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                    onGoHome = ::goHome,
                    onRequestTemporaryUnlock = {
                        notice = null
                        confirming = true
                        waitStartedAt = SystemClock.elapsedRealtime()
                    },
                    onCancelTemporaryUnlock = { confirming = false },
                    onStartTemporaryUnlock = ::startTemporaryUnlock,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateTarget(intent)
    }

    override fun onStart() {
        super.onStart()
        // 画面に戻ったら、待ち時間は最初から数え直す
        if (confirming) waitStartedAt = SystemClock.elapsedRealtime()
        loadStatus()
    }

    override fun onStop() {
        // 画面を離れたら、数えていた待ち時間を破棄する
        waitStartedAt = null
        super.onStop()
    }

    private fun updateTarget(intent: Intent) {
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        blockedLabel = (application as TaskLockApp).container.installedApps.labelOf(blockedPackage)
        if (!starting) {
            confirming = false
            notice = null
        }
    }

    private fun loadStatus() {
        lifecycleScope.launch { status = repository.temporaryUnlockStatus() }
    }

    private fun startTemporaryUnlock() {
        if (starting) return
        starting = true
        lifecycleScope.launch {
            when (val decision = repository.startTemporaryUnlock()) {
                is TemporaryUnlockDecision.Allowed -> {
                    if (repository.awaitTemporaryUnlockInSnapshot(decision.unlock)) {
                        openBlockedApp()
                    } else {
                        // 記録は保存済みなので、開始の失敗として扱わない（やり直させない）
                        Toast.makeText(
                            this@BlockActivity,
                            "一時解除を開始しました。もう一度アプリを開いてください",
                            Toast.LENGTH_LONG,
                        ).show()
                        goHome()
                    }
                }
                is TemporaryUnlockDecision.Rejected -> {
                    notice = decision.reason.message
                    confirming = false
                    starting = false
                    status = repository.temporaryUnlockStatus()
                }
            }
        }
    }

    /** ブロックしていたアプリを開き直す。起動用のインテントが無ければ画面を閉じるだけ */
    private fun openBlockedApp() {
        packageManager.getLaunchIntentForPackage(blockedPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { startActivity(it) }
        finish()
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
private fun BlockScreen(
    appLabel: String,
    status: TemporaryUnlockStatus?,
    confirming: Boolean,
    starting: Boolean,
    notice: String?,
    waitStartedAt: Long?,
    onOpenTasks: () -> Unit,
    onGoHome: () -> Unit,
    onRequestTemporaryUnlock: () -> Unit,
    onCancelTemporaryUnlock: () -> Unit,
    onStartTemporaryUnlock: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (confirming && status != null) {
                TemporaryUnlockConfirm(status, starting, waitStartedAt, onCancelTemporaryUnlock, onStartTemporaryUnlock)
            } else {
                Blocked(appLabel, status, notice, onOpenTasks, onGoHome, onRequestTemporaryUnlock)
            }
        }
    }
}

@Composable
private fun Blocked(
    appLabel: String,
    status: TemporaryUnlockStatus?,
    notice: String?,
    onOpenTasks: () -> Unit,
    onGoHome: () -> Unit,
    onRequestTemporaryUnlock: () -> Unit,
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
    Spacer(Modifier.height(16.dp))
    // 入口は「通常のブロック中で当日の残り回数がある」ときだけ。使い切った日は文言だけを出す
    when (status?.rejection) {
        null -> if (status != null) {
            TextButton(onClick = onRequestTemporaryUnlock) {
                Text("一時解除（${TemporaryUnlockPolicy.DURATION.toMinutes()}分）")
            }
        }
        TemporaryUnlockRejection.DAILY_LIMIT_REACHED ->
            Text(
                TemporaryUnlockRejection.DAILY_LIMIT_REACHED.message,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        else -> Unit
    }
    if (notice != null && notice != status?.rejection?.message) {
        Spacer(Modifier.height(8.dp))
        Text(notice, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TemporaryUnlockConfirm(
    status: TemporaryUnlockStatus,
    starting: Boolean,
    waitStartedAt: Long?,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    // 残り秒数は数え始めた時刻からの経過で求める（数え始めていない間は待ち時間いっぱい）
    fun secondsLeftAt(now: Long): Int =
        if (waitStartedAt == null) WAIT_SECONDS
        else (WAIT_SECONDS - ((now - waitStartedAt) / 1_000).toInt()).coerceAtLeast(0)
    var secondsLeft by remember(waitStartedAt) { mutableStateOf(secondsLeftAt(SystemClock.elapsedRealtime())) }
    LaunchedEffect(waitStartedAt) {
        while (waitStartedAt != null && secondsLeft > 0) {
            delay(200)
            secondsLeft = secondsLeftAt(SystemClock.elapsedRealtime())
        }
    }
    val minutes = TemporaryUnlockPolicy.DURATION.toMinutes()
    Text("一時解除", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    listOf(
        "一時解除は${minutes}分間です。${minutes}分後は、使用中でもロックに戻ります",
        "今日あと${status.remainingToday}回使えます（この解除で1回使います）",
        "今月${status.nextNumberThisMonth}回目になります",
        "タスクの達成にはなりません",
        "命に関わる緊急時は、電話アプリがいつでも使えます",
    ).forEach {
        Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onStart,
        enabled = waitStartedAt != null && secondsLeft == 0 && !starting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (secondsLeft > 0) "開始する（あと${secondsLeft}秒）" else "開始する")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, enabled = !starting, modifier = Modifier.fillMaxWidth()) { Text("やめる") }
}

/** 確認画面の待ち時間（秒）。DB には保存せず、開始の判定にも使わない */
private const val WAIT_SECONDS = 10
