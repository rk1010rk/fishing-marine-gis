package jp.tasklock.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import jp.tasklock.core.model.LockRule

@Database(
    entities = [
        TaskEntity::class,
        CompletionEntity::class,
        VerificationEntity::class,
        LockRuleEntity::class,
        LockedAppEntity::class,
        UnlockGrantEntity::class,
        EmergencyUnlockEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // v1→v2 は emergency_unlocks の追加だけ（DESIGN.md §9.6-2）。既存のテーブルとデータには触れない
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun completionDao(): CompletionDao
    abstract fun verificationDao(): VerificationDao
    abstract fun lockRuleDao(): LockRuleDao
    abstract fun lockedAppDao(): LockedAppDao
    abstract fun unlockGrantDao(): UnlockGrantDao
    abstract fun emergencyUnlockDao(): EmergencyUnlockDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tasklock.db")
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // MVPのロック条件は「1日1回、いずれか1つのタスクを完了」の1件のみ
                            db.execSQL(
                                "INSERT INTO lock_rules (type, paramsJson, active) VALUES (?, '{}', 1)",
                                arrayOf(LockRule.TYPE_DAILY_ANY_ONE_TASK),
                            )
                        }
                    },
                )
                .build()
    }
}
