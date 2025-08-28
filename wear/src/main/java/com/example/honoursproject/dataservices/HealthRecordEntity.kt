package com.example.honoursproject.dataservices

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "health_records")
data class HealthRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val timestamp: Long,
    val heartRate: Double,
    val stepCount: Int,
    val activeEnergyBurned: Double,
    val basalEnergyBurned: Double,
    val cyclePhase: String,
    val cycleDay: Int,
    val vo2Max: Double,
    val restingHeartRate: Double,
    val basalBodyTemperature: Double
)
@Dao
interface HealthRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HealthRecordEntity)

    @Query("SELECT * FROM health_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getRecordsSince(since: Long): Flow<List<HealthRecordEntity>>
    @Query("DELETE FROM health_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Database(entities = [HealthRecordEntity::class], version = 1)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthRecordDao(): HealthRecordDao

    companion object {
        @Volatile private var INSTANCE: HealthDatabase? = null

        fun getInstance(context: Context): HealthDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
                    "health_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}