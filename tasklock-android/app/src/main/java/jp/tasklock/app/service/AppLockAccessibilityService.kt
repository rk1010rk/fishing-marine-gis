package jp.tasklock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import jp.tasklock.app.TaskLockApp
import jp.tasklock.app.ui.BlockActivity
import java.time.Instant

/**
 * ロック対象アプリが前面に来たことを検知し、ブロック画面を重ねる。
 *
 * - 受け取るのは TYPE_WINDOW_STATE_CHANGED のパッケージ名のみ。画面内容は読まない（config で canRetrieveWindowContent=false）
 * - 判定は Repository が保持するメモリ上のスナップショットで行い、イベントごとにDBへアクセスしない
 * - ユーザーが設定からこのサービスを無効化すれば必ず回避できる。これはAndroidの仕様上の限界
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val repository by lazy { (application as TaskLockApp).container.repository }

    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Repository を生成し、ロック対象・解除状態のスナップショット読み込みを最初のイベントより前に始めておく
        repository
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (!repository.blockSnapshot.value.shouldBlock(pkg, Instant.now())) return

        // 1つのアプリ起動で複数のウィンドウイベントが連続するため、短時間の重複起動を抑止する
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastBlockedPackage && now - lastBlockedAt < DEBOUNCE_MS) return
        lastBlockedPackage = pkg
        lastBlockedAt = now

        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val DEBOUNCE_MS = 700L
    }
}
