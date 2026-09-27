package jp.tasklock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.ui.BlockActivity
import jp.tasklock.core.lock.BlockGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ロック対象アプリが前面に来たことを検知し、ブロック画面を重ねる。
 *
 * - 受け取るのは TYPE_WINDOW_STATE_CHANGED のパッケージ名のみ。画面内容は読まない（config で canRetrieveWindowContent=false）
 * - 判定は Repository が保持するメモリ上のスナップショットで行い、イベントごとにDBへアクセスしない
 * - スナップショットの未ロード中に開かれたアプリは、初回ロード完了時に再判定する（BlockGate）
 * - ユーザーが設定からこのサービスを無効化すれば必ず回避できる。これはAndroidの仕様上の限界
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val container by lazy { (application as TaskLockApp).container }
    private val gate by lazy { BlockGate(ownPackage = packageName) }

    // onAccessibilityEvent と同じメインスレッドで動かし、BlockGate への同時アクセスを避ける
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch {
            container.repository.blockSnapshot.filterNotNull().collect { snapshot ->
                gate.onSnapshot(snapshot, Instant.now(), SystemClock.elapsedRealtime(), ::exemptPackages)
                    ?.let(::showBlockScreen)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (gate.onWindowEvent(pkg, Instant.now(), SystemClock.elapsedRealtime(), ::exemptPackages)) {
            showBlockScreen(pkg)
        }
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
        scope.cancel()
        super.onDestroy()
    }
}
