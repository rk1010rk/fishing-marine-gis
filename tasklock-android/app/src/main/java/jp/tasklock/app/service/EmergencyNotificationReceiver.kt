package jp.tasklock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import jp.tasklock.app.TaskLockApp
import kotlinx.coroutines.launch

/**
 * 緊急解除の通知からの操作を受け取る（マニフェストで exported="false"。通知の PendingIntent からだけ呼ばれる）。
 * - [ACTION_STOP]: 通知の「緊急解除」。画面を開かずに緊急解除を開始する。開始できるかどうかは Repository が
 *   トランザクション内で DB を読み直して判定し、拒否されたら何も書き込まない（古い通知から押された場合など）。
 *   開始できればロック対象が空になり、サービスが通知を消す
 * - [ACTION_DISMISSED]: 利用者が通知を消した。勝手に出し直さないよう記録する
 */
class EmergencyNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISSED -> EmergencyNotification.markDismissed(context)
            ACTION_STOP -> {
                val container = (context.applicationContext as TaskLockApp).container
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        container.repository.startEmergencyStop()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "jp.tasklock.app.action.EMERGENCY_STOP"
        const val ACTION_DISMISSED = "jp.tasklock.app.action.EMERGENCY_NOTIFICATION_DISMISSED"
    }
}
