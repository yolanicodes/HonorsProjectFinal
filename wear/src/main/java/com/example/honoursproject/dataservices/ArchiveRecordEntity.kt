package com.example.honoursproject.dataservices

import android.content.Context
import androidx.room.*

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
}

@Database(entities = [ArchiveRecordEntity::class], version = 1)
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