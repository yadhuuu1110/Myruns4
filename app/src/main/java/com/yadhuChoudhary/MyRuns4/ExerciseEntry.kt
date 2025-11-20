package com.yadhuChoudhary.MyRuns4
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.*

@Entity(tableName = "exercise_table")
@TypeConverters(Converters::class)
data class ExerciseEntry(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,

    var inputType: Int = 0,              // 0: Manual, 1: GPS, 2: Automatic
    var activityType: Int = 0,           // Activity type index
    var dateTime: Calendar = Calendar.getInstance(),
    var duration: Double = 0.0,          // Duration in seconds
    var distance: Double = 0.0,          // Distance in miles
    var avgPace: Double = 0.0,           // Average pace
    var avgSpeed: Double = 0.0,          // Average speed in mph
    var calorie: Double = 0.0,           // Calories burnt
    var climb: Double = 0.0,             // Climb in feet
    var heartRate: Double = 0.0,         // Heart rate in bpm
    var comment: String = "",            // User comments
    var locationList: ByteArray? = null  // GPS coordinates as byte array
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExerciseEntry

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}