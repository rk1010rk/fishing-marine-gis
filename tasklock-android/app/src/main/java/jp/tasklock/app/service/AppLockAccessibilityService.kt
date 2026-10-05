package jp.tasklock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.ui.BlockActivity
import jp.tasklock.core.lock.BlockGate
import jp.tasklock.core.lock.BlockSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * ロック対象アプリが前面に来たことを検知し、ブロック画面を重ねる。
 *
 * - 受け取るのは TYPE_WINDOW_STATE_CHANGED のパッケージ名のみ。画面内容は読まない（config で canRetrieveWindowContent=false）
 * - 判定は Repository が保持するメモリ上のスナップショットで行い、イベントごとにDBへアクセスしない
 * - スナップショットの未ロード中に開かれたアプリは、初回ロード完了時に再判定する（BlockGate）
 * - 一時解除の期限は DB が変わらない変化なので、期限の時刻のタイマーと、画面の点灯・ロック解除のイベントで
 *   前面アプリを再判定する（タイマーが遅れても、イベントで補う。DESIGN.md §9.6-2）
 * - ユーザーが設定からこのサービスを無効化すれば必ず回避できる。これはAndroidの仕様上の限界
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val container by lazy { (application as TaskLockApp).container }
    private val gate by lazy { BlockGate(ownPackage = packageName) }

    // onAccessibilityEvent と同じメインスレッドで動かし、BlockGate への同時アクセスを避ける
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** gate に最後に渡したスナップショット */
    private var appliedSnapshot: BlockSnapshot? = null
    private var expiryJob: Job? = null

    /** 画面の点灯・ロック解除。登録時に Handler を指定しないので、メインスレッドで呼ばれる */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reevaluateForeground()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch {
            container.repository.blockSnapshot.filterNotNull().collect { applySnapshot(it) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // collector より先にイベントが来ても、Repository の最新のスナップショットで判定する
        // （一時解除を開始した直後に対象アプリを開いたときに、古いスナップショットで再びブロックしないため）
        syncSnapshot()
        if (gate.onWindowEvent(pkg, Instant.now(), SystemClock.elapsedRealtime(), ::exemptPackages)) {
            showBlockScreen(pkg)
        }
    }

    private fun syncSnapshot() {
        val latest = container.repository.blockSnapshot.value ?: return
        if (latest !== appliedSnapshot) applySnapshot(latest)
    }

    private fun applySnapshot(snapshot: BlockSnapshot) {
        if (snapshot === appliedSnapshot) return
        appliedSnapshot = snapshot
        gate.onSnapshot(snapshot, Instant.now(), SystemClock.elapsedRealtime(), ::exemptPackages)
            ?.let(::showBlockScreen)
        scheduleExpiry(snapshot)
    }

    /** 有効な一時解除があれば、期限の時刻に前面アプリを再判定する。スナップショットが変わるたびに掛け直す */
    private fun scheduleExpiry(snapshot: BlockSnapshot) {
        expiryJob?.cancel()
        expiryJob = null
        val temporary = snapshot.temporary ?: return
        val now = Instant.now()
        if (!temporary.isActiveAt(now)) return
        val wait = Duration.between(now, temporary.expiresAt).toMillis() + 1
        expiryJob = scope.launch {
            delay(wait)
            reevaluateForeground()
        }
    }

    /** 時刻だけが進んだときの再判定。連続して呼ばれても BlockGate の間引きで二重に表示しない */
    private fun reevaluateForeground() {
        syncSnapshot()
        gate.reevaluateForeground(Instant.now(), SystemClock.elapsedRealtime(), ::exemptPackages)
            ?.let(::showBlockScreen)
    }

    private fun exemptPackages(): Set<String> = container.installedApps.exemptPackages()

    private fun showBlockScreen(pkg: String) {
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }
}
