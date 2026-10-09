package dev.mikadore.nuntius.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_records")
data class LocalRecord(
    @PrimaryKey @ColumnInfo(name = "record_key") val key: String,
    val value: String,
)

@Dao
interface LocalRecordDao {
    @Query("SELECT * FROM local_records WHERE record_key = :key")
    fun observe(key: String): Flow<LocalRecord?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: LocalRecord)

    @Query("DELETE FROM local_records WHERE record_key = :key")
    suspend fun delete(key: String)
}

@Database(entities = [LocalRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localRecordDao(): LocalRecordDao
}
