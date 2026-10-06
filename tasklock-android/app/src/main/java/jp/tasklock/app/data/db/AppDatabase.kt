package jp.tasklock.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RenameTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
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
        TemporaryUnlockEntity::class,
        EmergencyStopEntity::class,
        EmergencyStopAppEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // v1→v2 は emergency_unlocks の追加だけ。v2→v3 は emergency_unlocks の名前の変更（temporary_unlocks）と
    // emergency_stops・emergency_stop_apps の追加（DESIGN.md §9.6-2）。既存のテーブルとデータには触れない
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = Migration2To3::class),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun completionDao(): CompletionDao
    abstract fun verificationDao(): VerificationDao
    abstract fun lockRuleDao(): LockRuleDao
    abstract fun lockedAppDao(): LockedAppDao
    abstract fun unlockGrantDao(): UnlockGrantDao
    abstract fun temporaryUnlockDao(): TemporaryUnlockDao
    abstract fun emergencyStopDao(): EmergencyStopDao

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

/** v2→v3: 一時解除の表の名前を temporary_unlocks に揃える（列とデータは変えない。DESIGN.md §9.6-2「v3 の DB 設計」） */
@RenameTable(fromTableName = "emergency_unlocks", toTableName = "temporary_unlocks")
class Migration2To3 : AutoMigrationSpec
