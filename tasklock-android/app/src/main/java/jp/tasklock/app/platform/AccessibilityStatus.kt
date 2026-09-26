package jp.tasklock.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import jp.tasklock.app.service.AppLockAccessibilityService

object AccessibilityStatus {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppLockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
