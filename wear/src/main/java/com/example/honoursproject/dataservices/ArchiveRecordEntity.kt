package com.example.honoursproject.dataservices

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "archive_records")
data class ArchiveRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
interface ArchiveRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ArchiveRecordEntity)

    @Query("SELECT * FROM archive_records ORDER BY timestamp ASC")
    suspend fun getAll(): List<ArchiveRecordEntity>

    @Query("SELECT * FROM archive_records WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getRecordsBetween(startTime: Long, endTime: Long): List<ArchiveRecordEntity>

    @Query("SELECT * FROM archive_records WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getRecordsBetweenFlow(startTime: Long, endTime: Long): Flow<List<ArchiveRecordEntity>>

    @Query("""
        SELECT 
            DATE(timestamp / 1000, 'unixepoch') as date,
            AVG(heartRate) as avgHeartRate,
            MAX(stepCount) as maxSteps,
            SUM(activeEnergyBurned) as totalEnergy,
            cyclePhase,
            cycleDay
        FROM archive_records 
        WHERE timestamp BETWEEN :startTime AND :endTime 
        GROUP BY DATE(timestamp / 1000, 'unixepoch')
        ORDER BY date ASC
    """)
    suspend fun getDailyAggregatesRaw(startTime: Long, endTime: Long): List<DailyAggregateRaw>
}



@Database(entities = [ArchiveRecordEntity::class], version = 3)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveRecordDao(): ArchiveRecordDao

    companion object {
        @Volatile private var INSTANCE: ArchiveDatabase? = null

        fun getInstance(context: Context): ArchiveDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ArchiveDatabase::class.java,
                    "archive_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}