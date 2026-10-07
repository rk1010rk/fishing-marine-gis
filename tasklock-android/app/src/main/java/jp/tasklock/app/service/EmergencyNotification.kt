package jp.tasklock.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.tasklock.app.ui.MainActivity

/**
 * 緊急解除の入口の通知（DESIGN.md §9.6-2「一時解除と緊急解除」）。ロック中と一時解除中に出す。
 *
 * 利用者が消した通知は、勝手に出し直さない（消された場合はブロック画面だけが入口になる）。そのため、
 * 状態を SharedPreferences に持ち、サービスやプロセスが作り直されても引き継ぐ:
 * - NONE: この通知を出していない。出す条件が true になったら出す
 * - POSTED: 出した。条件が true の間は出し直さない（文言も変えない）。ただし、利用者が消したのではなく
 *   通知が無くなっている場合（再起動・サービスの停止・権限の取り消しなど）は出し直す
 * - DISMISSED: 利用者が消した（削除の通知 [ACTION_DISMISSED] を受け取った）。条件が true の間は出さない
 * 条件が false になったら（サービスが動いている間の判定で）通知を消して NONE に戻し、次に true になったら新しく出す。
 * 利用者が消したことは削除の通知だけで判断し、通知が無いことからは推測しない。
 */
class EmergencyNotification(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val manager = NotificationManagerCompat.from(context)

    /** サービスが判定した「出す条件」を反映する。メインスレッドから呼ぶ */
    fun update(shouldShow: Boolean) {
        if (!shouldShow) {
            manager.cancel(NOTIFICATION_ID)
            setState(State.NONE)
            return
        }
        when (state()) {
            State.DISMISSED -> Unit
            State.POSTED -> if (!isActive()) post()
            State.NONE -> post()
        }
    }

    /** サービスの停止時。自分で出した通知を消すだけで、状態は変えない（次に動いたときに判定し直す） */
    fun onServiceStopped() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun post() {
        // 権限が無い・通知がオフの間は出さず、NONE のままにする（許可された後の判定で出す）
        if (!canPost()) {
            setState(State.NONE)
            return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ロック中")
            .setContentText("緊急解除はこちら")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(receiverIntent(EmergencyNotificationReceiver.ACTION_DISMISSED, REQUEST_DISMISSED))
            .addAction(0, "緊急解除", receiverIntent(EmergencyNotificationReceiver.ACTION_STOP, REQUEST_STOP))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            setState(State.POSTED)
        } catch (e: SecurityException) {
            // 判定の直後に権限が取り消された場合。出せていないので NONE のまま
            setState(State.NONE)
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun isActive(): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == NOTIFICATION_ID } == true

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "緊急解除の入口", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "ロック中に、緊急解除のボタンを表示します" }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun receiverIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, EmergencyNotificationReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun state(): State =
        prefs.getString(KEY_STATE, null)?.let { runCatching { State.valueOf(it) }.getOrNull() } ?: State.NONE

    private fun setState(state: State) {
        if (state() != state) prefs.edit().putString(KEY_STATE, state.name).apply()
    }

    private enum class State { NONE, POSTED, DISMISSED }

    companion object {
        private const val PREFS = "emergency_notification"
        private const val KEY_STATE = "state"
        private const val CHANNEL_ID = "emergency_stop_entry"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_DISMISSED = 3

        /** 利用者が通知を消したことを記録する（[EmergencyNotificationReceiver] から呼ぶ） */
        fun markDismissed(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATE, State.DISMISSED.name).apply()
        }

        /** アプリ内からサービスに判定し直してもらう（通知の権限を許可した後など）。サービスが動いていなければ何もしない */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
        }

        /** サービスの受信（RECEIVER_NOT_EXPORTED）だけが受け取る、アプリ内の判定し直しの要求 */
        const val ACTION_REFRESH = "jp.tasklock.app.action.REFRESH_EMERGENCY_NOTIFICATION"
    }
}
