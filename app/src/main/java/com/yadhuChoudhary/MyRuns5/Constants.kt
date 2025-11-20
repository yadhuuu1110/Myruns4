package com.yadhuChoudhary.MyRuns5

/**
 * Constants for MyRuns5 application
 * Contains all constant values used throughout the app
 */
object Constants {
    // ==================== SharedPreferences ====================
    const val PREFS_NAME = "MyRunsPrefs"
    const val PREF_UNIT = "unit_preference"

    // ==================== Unit Types ====================
    const val UNIT_KILOMETERS = 0
    const val UNIT_MILES = 1
    const val UNIT_METRIC = 0  // Same as UNIT_KILOMETERS
    const val UNIT_IMPERIAL = 1  // Same as UNIT_MILES

    // ==================== Input Types ====================
    const val INPUT_TYPE_MANUAL = 0
    const val INPUT_TYPE_GPS = 1
    const val INPUT_TYPE_AUTOMATIC = 2

    // ==================== Intent Extras ====================
    const val EXTRA_EXERCISE_ID = "exercise_id"
    const val EXTRA_INPUT_TYPE = "input_type"
    const val EXTRA_ACTIVITY_TYPE = "activity_type"

    // ==================== Activity Types Array ====================
    /**
     * Array of activity type names for display
     * Index corresponds to activity type ID
     *
     * NOTE: Order matters for database storage and display!
     * The classifier returns 0=Standing, 1=Walking, 2=Running
     * which gets mapped to these indices by TrackingService
     */
    val ACTIVITY_TYPES = arrayOf(
        "Running",              // 0
        "Walking",              // 1
        "Standing",             // 2
        "Cycling",              // 3
        "Hiking",               // 4
        "Downhill Skiing",      // 5
        "Cross-Country Skiing", // 6
        "Snowboarding",         // 7
        "Skating",              // 8
        "Swimming",             // 9
        "Mountain Biking",      // 10
        "Wheelchair",           // 11
        "Elliptical",           // 12
        "Other"                 // 13
    )

    // ==================== Activity Type Constants ====================
    /**
     * Direct references to activity types for easy code access
     * Used for mapping classifier output to app activity types
     */
    const val ACTIVITY_TYPE_RUNNING = 0
    const val ACTIVITY_TYPE_WALKING = 1
    const val ACTIVITY_TYPE_STANDING = 2
    const val ACTIVITY_TYPE_CYCLING = 3
    const val ACTIVITY_TYPE_HIKING = 4
    const val ACTIVITY_TYPE_DOWNHILL_SKIING = 5
    const val ACTIVITY_TYPE_CROSS_COUNTRY_SKIING = 6
    const val ACTIVITY_TYPE_SNOWBOARDING = 7
    const val ACTIVITY_TYPE_SKATING = 8
    const val ACTIVITY_TYPE_SWIMMING = 9
    const val ACTIVITY_TYPE_MOUNTAIN_BIKING = 10
    const val ACTIVITY_TYPE_WHEELCHAIR = 11
    const val ACTIVITY_TYPE_ELLIPTICAL = 12
    const val ACTIVITY_TYPE_OTHER = 13
}