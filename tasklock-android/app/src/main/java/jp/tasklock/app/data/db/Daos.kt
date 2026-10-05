package jp.tasklock.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE active = 1 ORDER BY createdAt")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE active = 1 ORDER BY createdAt")
    suspend fun getActive(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    /** 履歴を残すため物理削除はしない */
    @Query("UPDATE tasks SET active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}

@Dao
interface CompletionDao {
    @Insert
    suspend fun insert(completion: CompletionEntity): Long

    @Query("SELECT * FROM completions WHERE day = :day ORDER BY completedAt")
    suspend fun forDay(day: String): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE day = :day ORDER BY completedAt")
    fun observeForDay(day: String): Flow<List<CompletionEntity>>

    @Query(
        "SELECT endPage FROM completions WHERE taskId = :taskId AND endPage IS NOT NULL " +
            "ORDER BY completedAt DESC LIMIT 1",
    )
    suspend fun lastEndPage(taskId: Long): Int?
}

@Dao
interface VerificationDao {
    @Insert
    suspend fun insert(verification: VerificationEntity): Long

    @Query("SELECT * FROM verifications WHERE completionId IN (:completionIds)")
    suspend fun forCompletions(completionIds: List<Long>): List<VerificationEntity>

    @Query("SELECT v.* FROM verifications v JOIN completions c ON v.completionId = c.id WHERE c.day = :day")
    fun observeForDay(day: String): Flow<List<VerificationEntity>>
}

@Dao
interface LockRuleDao {
    @Query("SELECT * FROM lock_rules WHERE active = 1 ORDER BY id LIMIT 1")
    suspend fun getActive(): LockRuleEntity?
}

@Dao
interface LockedAppDao {
    @Query("SELECT * FROM locked_apps ORDER BY label")
    fun observeAll(): Flow<List<LockedAppEntity>>

    @Query("SELECT packageName FROM locked_apps")
    suspend fun getPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface UnlockGrantDao {
    @Insert
    suspend fun insert(grant: UnlockGrantEntity): Long

    @Query("SELECT * FROM unlock_grants WHERE day = :day ORDER BY expiresAt DESC LIMIT 1")
    suspend fun latestForDay(day: String): UnlockGrantEntity?

    @Query("SELECT * FROM unlock_grants ORDER BY expiresAt DESC LIMIT 1")
    suspend fun getLatest(): UnlockGrantEntity?

    /** 日付をまたいでも有効期限で判定できるよう、日付に依らず最新のものを監視する */
    @Query("SELECT * FROM unlock_grants ORDER BY expiresAt DESC LIMIT 1")
    fun observeLatest(): Flow<UnlockGrantEntity?>
}

@Dao
interface EmergencyUnlockDao {
    @Insert
    suspend fun insert(unlock: EmergencyUnlockEntity): Long

    /** その日に開始した一時解除の件数（1日の上限の判定に使う） */
    @Query("SELECT COUNT(*) FROM emergency_unlocks WHERE day = :day")
    suspend fun countForDay(day: String): Int

    /** 開始した日が [from]〜[to]（両端を含む、yyyy-MM-dd）の一時解除の件数（「今月◯回目」に使う） */
    @Query("SELECT COUNT(*) FROM emergency_unlocks WHERE day BETWEEN :from AND :to")
    suspend fun countBetween(from: String, to: String): Int

    @Query("SELECT * FROM emergency_unlocks ORDER BY expiresAt DESC LIMIT 1")
    suspend fun getLatest(): EmergencyUnlockEntity?

    /** unlock_grants と同じく、日付に依らず期限の最も遅いものを監視する */
    @Query("SELECT * FROM emergency_unlocks ORDER BY expiresAt DESC LIMIT 1")
    fun observeLatest(): Flow<EmergencyUnlockEntity?>
}
