package jp.tasklock.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager

data class AppInfo(val packageName: String, val label: String, val isDefaultSms: Boolean = false)

/**
 * ランチャーに表示されるアプリの一覧。
 * 自分自身・設定・ホームアプリ・電話アプリ・SMS アプリは、ロックすると端末操作や緊急連絡を妨げるため候補から除外する。
 */
class InstalledApps(private val context: Context) {

    /**
     * @param includeDefaultSms 既定の SMS アプリを除外せずに含める（[AppInfo.isDefaultSms] で識別できる）。
     *   登録済みの行を「除外中」と表示するためのもので、ロック対象への登録可否は [exemptPackages] で判定する
     */
    fun launchableApps(includeDefaultSms: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val excluded = exemptPackages()
        val sms = defaultSmsPackage()
        return query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg !in excluded || (includeDefaultSms && pkg == sms) }
            .distinctBy { it.first }
            .map { (pkg, label) -> AppInfo(pkg, label, isDefaultSms = pkg == sms) }
            .sortedBy { it.label }
    }

    fun labelOf(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * ロックしてはいけないパッケージ。選択時だけでなく、ブロック判定時（実行時）にも使う。
     * 既定のホーム/電話/SMS アプリはロック設定後に変わりうるため、呼ぶたびに問い合わせる（DB には保存しない）。
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
            defaultSmsPackage()?.let(::add)
        }
    }

    /** 既定の SMS アプリ。SMS 非対応の端末などで取得できない場合は null */
    fun defaultSmsPackage(): String? = Telephony.Sms.getDefaultSmsPackage(context)

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
