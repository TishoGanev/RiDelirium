package com.bgdeveloper.ridelirium.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "track_points", indices = [Index("session")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    /** Metres per second, never negative. */
    val speed: Double,
    /** Epoch milliseconds. */
    val timestamp: Long,
    @ColumnInfo(name = "session") val session: Int,
    // Added in CSV format 2. null on points saved before; negative means the location API had no value.
    val rawSpeed: Double? = null,
    val horizontalAccuracy: Double? = null,
    val verticalAccuracy: Double? = null,
    val speedAccuracy: Double? = null,
    val course: Double? = null,
    val courseAccuracy: Double? = null,
    /** Motion activity when the fix arrived: automotive, walking, cycling, stationary or unknown. */
    val activity: String? = null,
    /** 0 low, 1 medium, 2 high. */
    val activityConfidence: Int? = null,
    /** Highest total G-force (gravity included, about 1.0 at rest) since the previous point; null without an accelerometer. */
    val gForce: Double? = null,
)

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(point: TrackPointEntity)

    @Query("SELECT * FROM track_points ORDER BY timestamp")
    fun observeAll(): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE session = :session ORDER BY timestamp")
    fun observeSession(session: Int): Flow<List<TrackPointEntity>>

    @Query("SELECT COALESCE(MAX(session), 0) FROM track_points")
    suspend fun maxSession(): Int

    @Query("DELETE FROM track_points WHERE session = :session")
    suspend fun deleteSession(session: Int)
}

@Database(entities = [TrackPointEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackPointDao(): TrackPointDao

    companion object {
        /** Adds the G-force column; existing points keep NULL there. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN gForce REAL")
            }
        }

        /** Adds the accuracy, course and activity columns; existing points keep NULL there. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "rawSpeed REAL", "horizontalAccuracy REAL", "verticalAccuracy REAL", "speedAccuracy REAL",
                    "course REAL", "courseAccuracy REAL", "activity TEXT", "activityConfidence INTEGER",
                ).forEach { db.execSQL("ALTER TABLE track_points ADD COLUMN $it") }
            }
        }
    }
}
