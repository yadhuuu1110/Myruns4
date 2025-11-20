package com.yadhuChoudhary.MyRuns4
object Constants {
    // SharedPreferences
    const val PREFS_NAME = "MyRunsPrefs"
    const val PREF_UNIT = "unit_preference"

    // Unit types
    const val UNIT_KILOMETERS = 0
    const val UNIT_MILES = 1
    const val UNIT_METRIC = 0  // Same as UNIT_KILOMETERS
    const val UNIT_IMPERIAL = 1  // Same as UNIT_MILES

    // Input types
    const val INPUT_TYPE_MANUAL = 0
    const val INPUT_TYPE_GPS = 1
    const val INPUT_TYPE_AUTOMATIC = 2

    // Intent extras
    const val EXTRA_EXERCISE_ID = "exercise_id"
    const val EXTRA_INPUT_TYPE = "input_type"
    const val EXTRA_ACTIVITY_TYPE = "activity_type"

    // Activity types array
    val ACTIVITY_TYPES = arrayOf(
        "Running",
        "Walking",
        "Standing",
        "Cycling",
        "Hiking",
        "Downhill Skiing",
        "Cross-Country Skiing",
        "Snowboarding",
        "Skating",
        "Swimming",
        "Mountain Biking",
        "Wheelchair",
        "Elliptical",
        "Other"
    )

    const val ACTIVITY_TYPE_RUNNING = 0
    const val ACTIVITY_TYPE_WALKING = 1
    const val ACTIVITY_TYPE_STANDING = 2
    const val ACTIVITY_TYPE_CYCLING = 3

}