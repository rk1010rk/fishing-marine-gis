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

    /** 緊急解除の開始で、その時点のロック対象（表示名を含む）を emergency_stop_apps に写すために使う */
    @Query("SELECT * FROM locked_apps ORDER BY label")
    suspend fun getAll(): List<LockedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    /** 緊急解除の開始だけで使う（ChangePolicy を通さない意図した例外。DESIGN.md §9.6-2「v3 の DB 設計」） */
    @Query("DELETE FROM locked_apps")
    suspend fun deleteAll()
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
interface TemporaryUnlockDao {
    @Insert
    suspend fun insert(unlock: TemporaryUnlockEntity): Long

    /** その日に開始した一時解除の件数（1日の上限の判定に使う） */
    @Query("SELECT COUNT(*) FROM temporary_unlocks WHERE day = :day")
    suspend fun countForDay(day: String): Int

    /** 開始した日が [from]〜[to]（両端を含む、yyyy-MM-dd）の一時解除の件数（「今月◯回目」に使う） */
    @Query("SELECT COUNT(*) FROM temporary_unlocks WHERE day BETWEEN :from AND :to")
    suspend fun countBetween(from: String, to: String): Int

    @Query("SELECT * FROM temporary_unlocks ORDER BY expiresAt DESC LIMIT 1")
    suspend fun getLatest(): TemporaryUnlockEntity?

    /** unlock_grants と同じく、日付に依らず期限の最も遅いものを監視する */
    @Query("SELECT * FROM temporary_unlocks ORDER BY expiresAt DESC LIMIT 1")
    fun observeLatest(): Flow<TemporaryUnlockEntity?>
}

@Dao
interface EmergencyStopDao {
    @Insert
    suspend fun insert(stop: EmergencyStopEntity): Long

    @Insert
    suspend fun insertApps(apps: List<EmergencyStopAppEntity>)

    /** 有効な緊急解除（resumedAt が null）。0件か1件のはずだが、2件以上を検出できるよう一覧で返す */
    @Query("SELECT * FROM emergency_stops WHERE resumedAt IS NULL ORDER BY stoppedAt DESC")
    suspend fun getActive(): List<EmergencyStopEntity>

    @Query("SELECT * FROM emergency_stops WHERE resumedAt IS NULL ORDER BY stoppedAt DESC LIMIT 1")
    fun observeActive(): Flow<EmergencyStopEntity?>

    /** 再開の確定。まだ有効な行だけを更新し、更新した件数を返す（二重の再開を防ぐ） */
    @Query("UPDATE emergency_stops SET resumedAt = :resumedAt, reason = :reason WHERE id = :id AND resumedAt IS NULL")
    suspend fun markResumed(id: Long, resumedAt: Long, reason: String?): Int

    /** その緊急解除の時点のロック対象（復元候補） */
    @Query("SELECT * FROM emergency_stop_apps WHERE stopId = :stopId ORDER BY label")
    suspend fun appsFor(stopId: Long): List<EmergencyStopAppEntity>

    /** 緊急解除をした日が [from]〜[to]（両端を含む、yyyy-MM-dd）の件数（今月の回数に使う） */
    @Query("SELECT COUNT(*) FROM emergency_stops WHERE day BETWEEN :from AND :to")
    suspend fun countBetween(from: String, to: String): Int
}
