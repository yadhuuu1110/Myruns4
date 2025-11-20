package com.yadhuChoudhary.MyRuns4

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
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * TrackingService - Enhanced with Activity Recognition
 *
 * IMPROVEMENTS IN THIS VERSION:
 * 1. Uses TYPE_ACCELEROMETER (not LINEAR_ACCELERATION) for better compatibility
 * 2. Implements sliding window smoothing to prevent jittery activity changes
 * 3. Better emulator support with enhanced logging
 * 4. Clears activity predictions properly for responsive transitions
 */
class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        private const val TAG = "TrackingService"
    }

    private val binder = LocalBinder()

    // Location tracking
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // Sensor management for activity recognition
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var usingLinearAcceleration = false  // Track which sensor type we're using
    private val accelerometerQueue = ArrayBlockingQueue<Double>(1024)
    private var classificationThread: Thread? = null
    private var isClassifying = false

    // For gravity filtering when using TYPE_ACCELEROMETER
    private val gravity = FloatArray(3)
    private val alpha = 0.8f  // Low-pass filter constant

    // Activity classifier
    private var activityClassifier: ActivityClassifier? = null
    private val activityBuffer = mutableListOf<Double>()

    // Sliding window for activity smoothing (keeps last 10 predictions)
    // This prevents rapid switching between activities
    private val recentPredictions = mutableListOf<Int>()
    private val PREDICTION_WINDOW_SIZE = 10

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
    private var inputType: Int = Constants.INPUT_TYPE_GPS
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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // STRATEGY: Try LINEAR_ACCELERATION first (removes gravity automatically)
        // Fall back to ACCELEROMETER if not available (we'll remove gravity manually)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer != null) {
            usingLinearAcceleration = true
            android.util.Log.d(TAG, "✓ Using LINEAR_ACCELERATION sensor (gravity removed): ${accelerometer?.name}")
        } else {
            // Fallback to regular accelerometer
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingLinearAcceleration = false
            android.util.Log.w(TAG, "⚠ LINEAR_ACCELERATION not available, using ACCELEROMETER (will remove gravity manually): ${accelerometer?.name}")
        }

        if (accelerometer == null) {
            android.util.Log.e(TAG, "✗ No accelerometer sensors available at all!")
        }

        activityClassifier = ActivityClassifier()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
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

        // Clear activity prediction history
        recentPredictions.clear()

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

        // Start activity recognition if automatic mode
        if (inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            android.util.Log.d(TAG, "Starting activity recognition in AUTOMATIC mode")
            startActivityRecognition()
        }

        // Start location updates for GPS and Automatic modes
        if (inputType == Constants.INPUT_TYPE_GPS || inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            getLastKnownLocation()
            startLocationUpdates()
        }
    }

    /**
     * Starts activity recognition using accelerometer
     * IMPROVED: Better sensor registration and logging
     */
    private fun startActivityRecognition() {
        if (accelerometer == null) {
            android.util.Log.e(TAG, "Cannot start activity recognition - no sensor!")
            return
        }

        accelerometerQueue.clear()
        activityBuffer.clear()
        recentPredictions.clear()
        isClassifying = true

        // Register accelerometer listener
        // CHANGED: Use SENSOR_DELAY_GAME instead of FASTEST for better battery life
        val registered = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )

        android.util.Log.d(TAG, "Accelerometer registration: ${if (registered == true) "SUCCESS" else "FAILED"}")

        // Start classification thread
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

    /**
     * Sensor callback - receives accelerometer data
     * IMPROVED: Handles both LINEAR_ACCELERATION and ACCELEROMETER
     * Removes gravity manually if using regular accelerometer
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // Handle both sensor types
        val isCorrectSensor = (usingLinearAcceleration && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) ||
                (!usingLinearAcceleration && event.sensor.type == Sensor.TYPE_ACCELEROMETER)

        if (isCorrectSensor) {
            var x = event.values[0]
            var y = event.values[1]
            var z = event.values[2]

            // If using regular accelerometer, remove gravity using low-pass filter
            if (!usingLinearAcceleration) {
                // Low-pass filter to isolate gravity
                gravity[0] = alpha * gravity[0] + (1 - alpha) * x
                gravity[1] = alpha * gravity[1] + (1 - alpha) * y
                gravity[2] = alpha * gravity[2] + (1 - alpha) * z

                // Remove gravity to get linear acceleration
                x -= gravity[0]
                y -= gravity[1]
                z -= gravity[2]
            }

            // Calculate magnitude: m = sqrt(x² + y² + z²)
            val magnitude = FeatureExtractor.calculateMagnitude(x, y, z)

            // Log occasionally for debugging (every 100 readings)
            if (accelerometerQueue.size % 100 == 0) {
                val sensorType = if (usingLinearAcceleration) "LINEAR" else "ACCEL"
                android.util.Log.d(TAG, "[$sensorType] x=${"%.2f".format(x)}, y=${"%.2f".format(y)}, z=${"%.2f".format(z)}, mag=${"%.2f".format(magnitude)}, queue=${accelerometerQueue.size}")
            }

            // Add to queue for background processing
            try {
                accelerometerQueue.offer(magnitude)
            } catch (e: Exception) {
                // Queue full, skip reading
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        android.util.Log.d(TAG, "Sensor accuracy changed: $accuracy")
    }

    /**
     * Processes accelerometer readings and performs classification
     * IMPROVED: Uses sliding window for smooth activity transitions
     */
    private fun processAccelerometerReading(magnitude: Double) {
        activityBuffer.add(magnitude)

        // Process when we have 64 readings (one block)
        if (activityBuffer.size >= FeatureExtractor.BLOCK_CAPACITY) {
            // Extract features from the block
            val block = activityBuffer.take(FeatureExtractor.BLOCK_CAPACITY).toDoubleArray()
            val features = FeatureExtractor.extractFeatures(block)

            // Classify using embedded Weka classifier
            // Returns: 0=Standing, 1=Walking, 2=Running
            val predictedActivity = activityClassifier?.classify(features) ?: 0

            // Add to sliding window
            recentPredictions.add(predictedActivity)

            // Keep only last N predictions (sliding window)
            if (recentPredictions.size > PREDICTION_WINDOW_SIZE) {
                recentPredictions.removeAt(0)
            }

            // Determine overall activity using majority vote from sliding window
            val activityCounts = recentPredictions.groupingBy { it }.eachCount()
            val dominantActivity = activityCounts.maxByOrNull { it.value }?.key ?: predictedActivity

            android.util.Log.d(TAG, "Prediction: $predictedActivity, Window: $recentPredictions, Dominant: $dominantActivity")

            // Map classifier output to app activity types
            // Classifier: 0=Standing, 1=Walking, 2=Running
            // Constants: 0=Running, 1=Walking, 2=Standing
            val mappedActivity = when (dominantActivity) {
                0 -> Constants.ACTIVITY_TYPE_STANDING  // 2
                1 -> Constants.ACTIVITY_TYPE_WALKING   // 1
                2 -> Constants.ACTIVITY_TYPE_RUNNING   // 0
                else -> Constants.ACTIVITY_TYPE_STANDING
            }

            // Update current entry with detected activity
            currentEntry?.activityType = mappedActivity

            // Post activity label for UI
            val activityLabel = activityClassifier?.getActivityLabel(dominantActivity) ?: "Standing"
            _detectedActivityLiveData.postValue(activityLabel)

            android.util.Log.d(TAG, "Activity updated: $activityLabel (mapped=$mappedActivity)")

            // Remove processed readings from buffer
            repeat(FeatureExtractor.BLOCK_CAPACITY) {
                if (activityBuffer.isNotEmpty()) {
                    activityBuffer.removeAt(0)
                }
            }
        }
    }

    private fun stopActivityRecognition() {
        android.util.Log.d(TAG, "Stopping activity recognition")
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
            0 -> 100.0  // Running
            1 -> 80.0   // Walking
            2 -> 30.0   // Standing
            3 -> 70.0   // Cycling
            4 -> 95.0   // Hiking
            5 -> 110.0  // Downhill Skiing
            6 -> 120.0  // Cross-Country Skiing
            7 -> 100.0  // Snowboarding
            8 -> 85.0   // Skating
            9 -> 130.0  // Swimming
            10 -> 75.0  // Mountain Biking
            11 -> 60.0  // Wheelchair
            12 -> 75.0  // Elliptical
            else -> 80.0
        }

        return distance * caloriesPerMile
    }

    fun getCurrentEntry(): ExerciseEntry? {
        return currentEntry
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