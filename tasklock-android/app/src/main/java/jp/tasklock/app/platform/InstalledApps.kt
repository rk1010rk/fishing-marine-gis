package jp.tasklock.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.telecom.TelecomManager

data class AppInfo(val packageName: String, val label: String)

/**
 * ランチャーに表示されるアプリの一覧。
 * 自分自身・設定・ホームアプリ・電話アプリは、ロックすると端末操作や緊急連絡を妨げるため候補から除外する。
 */
class InstalledApps(private val context: Context) {

    fun launchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val excluded = exemptPackages()
        return query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg !in excluded }
            .distinctBy { it.first }
            .map { (pkg, label) -> AppInfo(pkg, label) }
            .sortedBy { it.label }
    }

    fun labelOf(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * ロックしてはいけないパッケージ。選択時だけでなく、ブロック判定時（実行時）にも使う。
     * 既定のホーム/電話アプリはロック設定後に変わりうるため、呼ぶたびに問い合わせる。
     */
    fun exemptPackages(): Set<String> {
        val homes = query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            .map { it.activityInfo.packageName }
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return buildSet {
            add(context.packageName)
            add("com.android.settings")
            addAll(homes)
            dialer?.let(::add)
        }
    }

    private fun query(intent: Intent): List<ResolveInfo> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }
}
