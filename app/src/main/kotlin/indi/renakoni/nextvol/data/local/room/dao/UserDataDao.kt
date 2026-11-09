package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import indi.renakoni.nextvol.data.userdata.UserDataWrites
import io.nightfish.lightnovelreader.api.userdata.UserDataDaoApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

@Dao
abstract class UserDataDao: UserDataDaoApi {
    private val settingsWrites = UserDataWrites()

    override fun submit(path: String, write: suspend () -> Unit): Deferred<Unit> = settingsWrites.submit(path, write)

    internal suspend fun closeSettingsWrites() = settingsWrites.close()

    @Query("replace into user_data (path, `group`, type, value) " +
            "values (:path, :group, :type, :value)")
    abstract override suspend fun insert(path: String, group: String, type: String, value: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(userDataEntity: UserDataEntity)

    @Query("select value from user_data where path = :path")
    abstract override suspend fun get(path: String): String?

    /** Share Room's write boundary with download cleanup and backup restore transactions. */
    @Transaction
    open suspend fun update(path: String, group: String, type: String, transform: (String?) -> String) {
        insert(path, group, type, transform(get(path)))
    }

    @Query("select value from user_data where path = :path")
    abstract override fun getFlow(path: String): Flow<String?>

    @Query("select * from user_data where path = :path")
    abstract fun getEntity(path: String): UserDataEntity?

    @Query("select * from user_data where `group` = :group")
    abstract fun getGroupValues(group: String): List<UserDataEntity>

    @Query("delete from user_data where path = :path")
    abstract override suspend fun remove(path: String)

    @Query("select * from user_data")
    abstract fun getAllEntities(): List<UserDataEntity>
}
