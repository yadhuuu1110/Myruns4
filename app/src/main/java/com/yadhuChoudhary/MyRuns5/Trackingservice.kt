package com.yadhuChoudhary.MyRuns5

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.yadhuChoudhary.MyRuns5.Globals
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * OPTIMIZED TrackingService for YOUR MyRuns4 app
 * Works with professor's Globals.kt structure
 *
 * Key optimizations from MyRuns5:
 * 1. Strong gravity filtering (α=0.99)
 * 2. Noise threshold filtering (0.2)
 * 3. Instant 3-sample detection
 * 4. Calibrated thresholds (0.5, 5.0)
 */
class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        private const val TAG = Globals.TAG

        // 🔥 CRITICAL OPTIMIZATION CONSTANTS
        private const val NOISE_THRESHOLD = 0.2  // Filter out sensor drift/noise
        private const val GRAVITY_FILTER_ALPHA = 0.99f  // Very strong gravity filtering
    }

    private val binder = LocalBinder()

    // Location tracking
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // Sensor management for activity recognition
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var usingLinearAcceleration = false
    private val accelerometerQueue = ArrayBlockingQueue<Double>(1024)
    private var classificationThread: Thread? = null
    private var isClassifying = false

    // For gravity filtering when using TYPE_ACCELEROMETER
    private val gravity = FloatArray(3)

    // Emulator detection
    private var runningOnEmulator = false

    // Activity classifier (backup, not used for instant detection)
    private var activityClassifier: ActivityClassifier? = null

    // 🔥 INSTANT DETECTION VARIABLES
    private val recentMagnitudes = ArrayDeque<Double>(3)
    private val MAGNITUDE_WINDOW_SIZE = 3
    private val STANDING_THRESHOLD = 0.5
    private val WALKING_THRESHOLD = 5.0
    private var currentDisplayedActivity = 0

    // Debug counters
    private var sensorReadingCount = 0
    private var classificationCount = 0

    // Tracks time spent in each activity to determine the dominant one
    private var lastActivityChangeTime: Long = 0
    private var currentDetectedActivity: Int = Globals.ACTIVITY_ID_STANDING
    private val activityDurations = mutableMapOf<Int, Long>()

    // LiveData for UI updates
    private val _exerciseEntryLiveData = MutableLiveData<ExerciseEntry>()
    val exerciseEntryLiveData: LiveData<ExerciseEntry> = _exerciseEntryLiveData

    private val _currentSpeedLiveData = MutableLiveData<Double>()
    val currentSpeedLiveData: LiveData<Double> = _currentSpeedLiveData

    private val _detectedActivityLiveData = MutableLiveData<String>()
    val detectedActivityLiveData: LiveData<String> = _detectedActivityLiveData

    // Tracking state
    private var currentEntry: ExerciseEntry? = null
    private val locationList = mutableListOf<LatLng>()
    private var startTime: Long = 0
    private var totalDistance: Double = 0.0
    private var lastLocation: Location? = null
    private var maxAltitude: Double = 0.0
    private var minAltitude: Double = Double.MAX_VALUE
    private var inputType: Int = 1  // 1 = GPS, 2 = AUTOMATIC (your app's values)
    private var activityType: Int = 0
    private var isFirstLocationReceived = false

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        runningOnEmulator = isEmulator()
        if (runningOnEmulator) {
            android.util.Log.w(TAG, "WARNING: Running on emulator - activity recognition may not work")
        } else {
            android.util.Log.i(TAG, "Running on real device")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Prefer LINEAR_ACCELERATION if available
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer != null) {
            usingLinearAcceleration = true
            android.util.Log.d(TAG, "Using LINEAR_ACCELERATION sensor: ${accelerometer?.name}")
        } else {
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingLinearAcceleration = false
            android.util.Log.w(TAG, "Using ACCELEROMETER with gravity filtering: ${accelerometer?.name}")
        }

        if (accelerometer == null) {
            android.util.Log.e(TAG, "No accelerometer sensors available")
        }

        activityClassifier = ActivityClassifier()
        createNotificationChannel()
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.PRODUCT.contains("sdk"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, 1)  // Default to GPS
                activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, 0)
                startTracking()
            }
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        startTime = System.currentTimeMillis()
        totalDistance = 0.0
        locationList.clear()
        lastLocation = null
        maxAltitude = 0.0
        minAltitude = Double.MAX_VALUE
        isFirstLocationReceived = false

        // Clear activity detection history
        recentMagnitudes.clear()
        currentDisplayedActivity = 0

        // Reset debug counters
        sensorReadingCount = 0
        classificationCount = 0

        // Reset activity duration tracking
        activityDurations.clear()
        lastActivityChangeTime = System.currentTimeMillis()
        currentDetectedActivity = Globals.ACTIVITY_ID_STANDING

        _currentSpeedLiveData.postValue(0.0)

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startTime

        currentEntry = ExerciseEntry(
            id = 0,
            inputType = inputType,
            activityType = activityType,
            dateTime = calendar,
            duration = 0.0,
            distance = 0.0,
            avgSpeed = 0.0,
            calorie = 0.0,
            climb = 0.0,
            locationList = null
        )

        startForeground(NOTIFICATION_ID, createNotification())

        // Start activity recognition for automatic mode
        if (inputType == 2) {  // 2 = AUTOMATIC
            android.util.Log.d(TAG, "Starting AUTOMATIC mode with activity recognition")
            _detectedActivityLiveData.postValue("Standing")
            startActivityRecognition()
        } else {
            android.util.Log.d(TAG, "Starting ${if (inputType == 1) "GPS" else "MANUAL"} mode")
        }

        // Start location updates for GPS and Automatic modes
        if (inputType == 1 || inputType == 2) {  // GPS or AUTOMATIC
            getLastKnownLocation()
            startLocationUpdates()
        }
    }

    private fun startActivityRecognition() {
        if (accelerometer == null) {
            android.util.Log.e(TAG, "Cannot start activity recognition - no sensor available")
            return
        }

        accelerometerQueue.clear()
        recentMagnitudes.clear()
        currentDisplayedActivity = 0
        isClassifying = true

        // Initialize gravity filter to zero
        gravity[0] = 0f
        gravity[1] = 0f
        gravity[2] = 0f

        // 🔥 FASTEST SAMPLING for instant response
        val registered = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        android.util.Log.d(TAG, "Accelerometer registration: ${if (registered == true) "SUCCESS" else "FAILED"}")
        android.util.Log.d(TAG, "Using ${if (usingLinearAcceleration) "LINEAR_ACCELERATION" else "ACCELEROMETER with gravity filter"}")
        android.util.Log.d(TAG, "Noise threshold: $NOISE_THRESHOLD, Gravity alpha: $GRAVITY_FILTER_ALPHA")

        // Start background thread for processing
        classificationThread = thread {
            android.util.Log.d(TAG, "Classification thread started")
            while (isClassifying) {
                try {
                    val reading = accelerometerQueue.take()
                    processAccelerometerReading(reading)
                } catch (e: InterruptedException) {
                    android.util.Log.d(TAG, "Classification thread interrupted")
                    break
                }
            }
            android.util.Log.d(TAG, "Classification thread stopped")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val isCorrectSensor = (usingLinearAcceleration && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) ||
                (!usingLinearAcceleration && event.sensor.type == Sensor.TYPE_ACCELEROMETER)

        if (isCorrectSensor) {
            var x = event.values[0]
            var y = event.values[1]
            var z = event.values[2]

            // 🔥 STRONG GRAVITY FILTERING (if using regular accelerometer)
            if (!usingLinearAcceleration) {
                gravity[0] = GRAVITY_FILTER_ALPHA * gravity[0] + (1 - GRAVITY_FILTER_ALPHA) * x
                gravity[1] = GRAVITY_FILTER_ALPHA * gravity[1] + (1 - GRAVITY_FILTER_ALPHA) * y
                gravity[2] = GRAVITY_FILTER_ALPHA * gravity[2] + (1 - GRAVITY_FILTER_ALPHA) * z

                x -= gravity[0]
                y -= gravity[1]
                z -= gravity[2]
            }

            // Calculate magnitude
            val rawMagnitude = FeatureExtractor.calculateMagnitude(x, y, z)

            // 🔥 NOISE FILTERING - Critical for eliminating false "walking" detection
            val filteredMagnitude = if (rawMagnitude < NOISE_THRESHOLD) 0.0 else rawMagnitude

            sensorReadingCount++

            // Log every 50 readings
            if (sensorReadingCount % 50 == 0) {
                val sensorType = if (usingLinearAcceleration) "LINEAR" else "ACCEL"
                val gravityMag = if (!usingLinearAcceleration) {
                    " grav=${String.format("%.2f", FeatureExtractor.calculateMagnitude(gravity[0], gravity[1], gravity[2]))}"
                } else ""

                android.util.Log.d(TAG, "[$sensorType] raw=${String.format("%.3f", rawMagnitude)} " +
                        "filtered=${String.format("%.3f", filteredMagnitude)}$gravityMag " +
                        "queue=${accelerometerQueue.size} count=$sensorReadingCount")
            }

            try {
                if (!accelerometerQueue.offer(filteredMagnitude)) {
                    accelerometerQueue.clear()
                    accelerometerQueue.offer(filteredMagnitude)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error adding to queue: ${e.message}")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val accuracyStr = when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            else -> "UNKNOWN"
        }
        android.util.Log.d(TAG, "Sensor accuracy: $accuracyStr ($accuracy)")
    }

    // 🔥 INSTANT THRESHOLD-BASED DETECTION
    private fun processAccelerometerReading(magnitude: Double) {
        // Rolling 3-sample window
        if (recentMagnitudes.size >= MAGNITUDE_WINDOW_SIZE) {
            recentMagnitudes.removeFirst()
        }
        recentMagnitudes.addLast(magnitude)

        if (recentMagnitudes.size < MAGNITUDE_WINDOW_SIZE) return

        classificationCount++

        // Calculate average magnitude
        val avgMag = recentMagnitudes.average()

        // 🔥 INSTANT THRESHOLD DETECTION
        // After strong filtering, magnitudes fall into clear ranges:
        // Standing: 0.0-0.3 m/s²
        // Walking: 0.5-4.0 m/s²
        // Running: 5.0+ m/s²
        val detectedActivity = when {
            avgMag < STANDING_THRESHOLD -> 0  // Standing
            avgMag < WALKING_THRESHOLD -> 1   // Walking
            else -> 2                          // Running
        }

        val activityLabels = arrayOf("Standing", "Walking", "Running")
        val activityLabel = activityLabels[detectedActivity]

        // Map to professor's Globals constants
        // Detection: 0=Standing, 1=Walking, 2=Running
        // Globals:   0=Standing, 1=Walking, 2=Running (SAME!)
        val mappedActivity = when (detectedActivity) {
            0 -> Globals.ACTIVITY_ID_STANDING   // 0
            1 -> Globals.ACTIVITY_ID_WALKING    // 1
            2 -> Globals.ACTIVITY_ID_RUNNING    // 2
            else -> Globals.ACTIVITY_ID_STANDING
        }

        // Track time spent in each activity
        val currentTime = System.currentTimeMillis()
        if (mappedActivity != currentDisplayedActivity) {
            val timeSpent = currentTime - lastActivityChangeTime
            activityDurations[currentDisplayedActivity] =
                activityDurations.getOrDefault(currentDisplayedActivity, 0L) + timeSpent

            android.util.Log.d(TAG, "⚡ INSTANT Change: ${getActivityName(currentDisplayedActivity)} " +
                    "→ ${getActivityName(mappedActivity)} (avg=${String.format("%.2f", avgMag)})")

            currentDisplayedActivity = mappedActivity
            lastActivityChangeTime = currentTime
        }

        // 🔥 INSTANT UI UPDATE
        currentEntry?.activityType = mappedActivity
        _detectedActivityLiveData.postValue(activityLabel)

        // Log first 30 detections
        if (classificationCount <= 30) {
            android.util.Log.d(TAG, "#$classificationCount: avg=${String.format("%.2f", avgMag)} → $activityLabel")
        } else if (classificationCount % 50 == 0) {
            android.util.Log.d(TAG, "#$classificationCount: avg=${String.format("%.2f", avgMag)} → $activityLabel")
        }
    }

    private fun getActivityName(activityType: Int): String {
        return when (activityType) {
            Globals.ACTIVITY_ID_RUNNING -> "Running"
            Globals.ACTIVITY_ID_WALKING -> "Walking"
            Globals.ACTIVITY_ID_STANDING -> "Standing"
            else -> "Unknown"
        }
    }

    private fun stopActivityRecognition() {
        android.util.Log.d(TAG, "Stopping activity recognition")
        android.util.Log.d(TAG, "Stats: $sensorReadingCount readings, $classificationCount classifications")
        isClassifying = false
        sensorManager?.unregisterListener(this)
        classificationThread?.interrupt()
        classificationThread = null
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 3f
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    onLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Location permission error: ${e.message}")
        }
    }

    private fun getLastKnownLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                location?.let {
                    onLocationUpdate(it)
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Location permission error: ${e.message}")
        }
    }

    private fun onLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        locationList.add(latLng)

        isFirstLocationReceived = true

        val currentSpeed = if (location.hasSpeed()) {
            (location.speed * 2.23694).coerceAtLeast(0.0)
        } else if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location) / 1609.34
            val timeDiff = (location.time - lastLocation!!.time) / 1000.0 / 3600.0
            if (timeDiff > 0) {
                (distance / timeDiff).coerceAtLeast(0.0)
            } else {
                0.0
            }
        } else {
            0.0
        }

        _currentSpeedLiveData.postValue(currentSpeed)

        if (lastLocation != null) {
            val distanceMeters = lastLocation!!.distanceTo(location)
            totalDistance += distanceMeters / 1609.34
        }

        if (location.hasAltitude()) {
            val altitude = location.altitude * 3.28084
            if (altitude > maxAltitude) maxAltitude = altitude
            if (altitude < minAltitude) minAltitude = altitude
        }

        lastLocation = location
        updateExerciseEntry()
    }

    private fun updateExerciseEntry() {
        val currentTime = System.currentTimeMillis()
        val duration = (currentTime - startTime) / 1000.0

        val avgSpeed = if (duration > 0) {
            (totalDistance / (duration / 3600.0))
        } else {
            0.0
        }

        val climb = if (maxAltitude > 0 && minAltitude < Double.MAX_VALUE) {
            maxAltitude - minAltitude
        } else {
            0.0
        }

        val calorie = calculateCalories(totalDistance, duration, currentEntry?.activityType ?: activityType)

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startTime

        val locationListBytes = if (locationList.isNotEmpty()) {
            LocationUtils.serializeLocationList(locationList)
        } else {
            null
        }

        currentEntry = ExerciseEntry(
            id = currentEntry?.id ?: 0,
            inputType = inputType,
            activityType = currentEntry?.activityType ?: activityType,
            dateTime = calendar,
            duration = duration,
            distance = totalDistance,
            avgSpeed = avgSpeed,
            calorie = calorie,
            climb = climb,
            locationList = locationListBytes
        )

        _exerciseEntryLiveData.postValue(currentEntry!!)
    }

    private fun calculateCalories(distance: Double, duration: Double, activityType: Int): Double {
        val caloriesPerMile = when (activityType) {
            0 -> 100.0  // Running/Standing
            1 -> 80.0   // Walking
            2 -> 100.0  // Running
            else -> 80.0
        }
        return distance * caloriesPerMile
    }

    fun getCurrentEntry(): ExerciseEntry? {
        return currentEntry
    }

    fun finalizeActivityType() {
        if (inputType == 2) {  // AUTOMATIC mode
            val currentTime = System.currentTimeMillis()
            val finalTimeSpent = currentTime - lastActivityChangeTime
            activityDurations[currentDetectedActivity] =
                activityDurations.getOrDefault(currentDetectedActivity, 0L) + finalTimeSpent

            val dominantActivity = activityDurations.maxByOrNull { it.value }?.key

            if (dominantActivity != null) {
                currentEntry?.activityType = dominantActivity

                android.util.Log.d(TAG, "Activity Duration Summary:")
                activityDurations.entries.sortedByDescending { it.value }.forEach { (activityType, duration) ->
                    val seconds = duration / 1000.0
                    val minutes = seconds / 60.0
                    android.util.Log.d(TAG, "   ${getActivityName(activityType)}: ${String.format("%.1f", seconds)}s (${String.format("%.1f", minutes)}m)")
                }
                android.util.Log.d(TAG, "Dominant activity: ${getActivityName(dominantActivity)} - saving as final activity type")

                updateExerciseEntry()
            }
        }
    }

    private fun stopTracking() {
        android.util.Log.d(TAG, "Stopping tracking")

        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }

        stopActivityRecognition()

        _currentSpeedLiveData.postValue(0.0)

        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MapDisplayActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyRuns4 Tracking")
            .setContentText("Tracking your activity...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        android.util.Log.d(TAG, "Service destroyed")
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        stopActivityRecognition()
        super.onDestroy()
    }
}