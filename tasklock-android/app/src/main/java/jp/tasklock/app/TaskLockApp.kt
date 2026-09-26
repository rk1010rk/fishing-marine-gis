package jp.tasklock.app

import android.app.Application
import jp.tasklock.app.data.TaskLockRepository
import jp.tasklock.app.data.db.AppDatabase
import jp.tasklock.app.platform.InstalledApps
import jp.tasklock.app.platform.UsageStatsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** DIライブラリは使わず、依存はここで手組みする（MVPでは十分） */
class TaskLockApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val usageStats = UsageStatsReader(app)
    val installedApps = InstalledApps(app)
    val repository = TaskLockRepository(AppDatabase.build(app), usageStats, installedApps::exemptPackages, appScope)
}
