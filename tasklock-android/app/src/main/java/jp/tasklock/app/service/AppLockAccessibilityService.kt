package jp.tasklock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.ui.BlockActivity
import jp.tasklock.core.lock.BlockDebouncer
import java.time.Instant

/**
 * ロック対象アプリが前面に来たことを検知し、ブロック画面を重ねる。
 *
 * - 受け取るのは TYPE_WINDOW_STATE_CHANGED のパッケージ名のみ。画面内容は読まない（config で canRetrieveWindowContent=false）
 * - 判定は Repository が保持するメモリ上のスナップショットで行い、イベントごとにDBへアクセスしない
 * - ユーザーが設定からこのサービスを無効化すれば必ず回避できる。これはAndroidの仕様上の限界
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val container by lazy { (application as TaskLockApp).container }
    private val repository by lazy { container.repository }
    private val debouncer = BlockDebouncer()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Repository を生成し、ロック対象・解除状態のスナップショット読み込みを最初のイベントより前に始めておく
        repository
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 自アプリ（ブロック画面）やホームのイベントも debouncer に渡し、間引き状態をリセットさせる
        val block = pkg != packageName && shouldBlock(pkg)
        if (!debouncer.onWindowEvent(pkg, block, SystemClock.elapsedRealtime())) return

        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun shouldBlock(pkg: String): Boolean {
        val snapshot = repository.blockSnapshot.value
        // ロック対象でなければ PackageManager への問い合わせをせずに終える
        if (pkg !in snapshot.lockedPackages) return false
        return snapshot.shouldBlock(pkg, Instant.now(), container.installedApps.exemptPackages())
    }

    override fun onInterrupt() = Unit
}
