package com.yadhuChoudhary.MyRuns5
import android.content.Context
import androidx.preference.PreferenceManager

object UnitConverter {

    fun getUnitPreference(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pref = prefs.getString("unit_pref", "Miles")
        return pref ?: "Miles"
    }

    fun isMetric(context: Context): Boolean {
        return getUnitPreference(context) == "Kilometers"
    }

    fun convertDistance(distanceInMiles: Double, context: Context): Double {
        return if (isMetric(context)) {
            distanceInMiles * 1.60934  // Miles to Kilometers
        } else {
            distanceInMiles
        }
    }

    fun formatDistance(distanceInMiles: Double, context: Context): String {
        val converted = convertDistance(distanceInMiles, context)
        val unit = if (isMetric(context)) "Kilometers" else "Miles"
        return String.format("%.2f %s", converted, unit)
    }

    fun getDistanceUnit(context: Context): String {
        return if (isMetric(context)) "Kilometers" else "Miles"
    }

    fun getDistanceUnitShort(context: Context): String {
        return if (isMetric(context)) "km" else "mi"
    }

    fun convertClimb(climbInFeet: Double, context: Context): Double {
        return if (isMetric(context)) {
            climbInFeet * 0.3048  // Feet to Meters
        } else {
            climbInFeet
        }
    }

    fun formatClimb(climbInFeet: Double, context: Context): String {
        val converted = convertClimb(climbInFeet, context)
        val unit = if (isMetric(context)) "meters" else "feet"
        return String.format("%.2f %s", converted, unit)
    }

    fun formatDuration(durationInSeconds: Double): String {
        val hours = (durationInSeconds / 3600).toInt()
        val minutes = ((durationInSeconds % 3600) / 60).toInt()
        val seconds = (durationInSeconds % 60).toInt()

        return when {
            hours > 0 -> String.format("%dh %dm %ds", hours, minutes, seconds)
            minutes > 0 -> String.format("%dm %ds", minutes, seconds)
            else -> String.format("%ds", seconds)
        }
    }

    fun formatCalories(calories: Double): String {
        return String.format("%.0f", calories)
    }
}