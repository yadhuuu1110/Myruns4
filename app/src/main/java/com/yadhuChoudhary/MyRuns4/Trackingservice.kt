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
class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        private const val TAG = "TrackingService"

        // Tunable parameters for activity recognition
        private const val NOISE_THRESHOLD = 0.15  // Ignore movements below this magnitude
        private const val GRAVITY_FILTER_ALPHA = 0.95f  // Higher = more filtering
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

    // Activity classifier
    private var activityClassifier: ActivityClassifier? = null
    private val activityBuffer = mutableListOf<Double>()

    // Sliding window for activity smoothing (keeps last 10 predictions)
    private val recentPredictions = mutableListOf<Int>()
    private val PREDICTION_WINDOW_SIZE = 10

    // Debug counters
    private var sensorReadingCount = 0
    private var classificationCount = 0

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

        // Detect if running on emulator
        runningOnEmulator = isEmulator()
        if (runningOnEmulator) {
            android.util.Log.w(TAG, "⚠️ RUNNING ON EMULATOR - Activity recognition may not work correctly!")
        } else {
            android.util.Log.i(TAG, "✓ Running on real device")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Try LINEAR_ACCELERATION first, fall back to ACCELEROMETER
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer != null) {
            usingLinearAcceleration = true
            android.util.Log.d(TAG, "✓ Using LINEAR_ACCELERATION sensor: ${accelerometer?.name}")
        } else {
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingLinearAcceleration = false
            android.util.Log.w(TAG, "⚠ Using ACCELEROMETER with gravity filtering: ${accelerometer?.name}")
        }

        if (accelerometer == null) {
            android.util.Log.e(TAG, "✗ No accelerometer sensors available!")
        }

        activityClassifier = ActivityClassifier()
        createNotificationChannel()
    }

    /**
     * Detects if app is running on emulator
     */
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

        // Reset debug counters
        sensorReadingCount = 0
        classificationCount = 0

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
            android.util.Log.d(TAG, "🤖 Starting AUTOMATIC mode with activity recognition")
            startActivityRecognition()
        } else {
            android.util.Log.d(TAG, "📍 Starting ${if (inputType == Constants.INPUT_TYPE_GPS) "GPS" else "MANUAL"} mode")
        }

        // Start location updates for GPS and Automatic modes
        if (inputType == Constants.INPUT_TYPE_GPS || inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            getLastKnownLocation()
            startLocationUpdates()
        }
    }

    /**
     * Starts activity recognition using accelerometer
     */
    private fun startActivityRecognition() {
        if (accelerometer == null) {
            android.util.Log.e(TAG, "❌ Cannot start activity recognition - no sensor!")
            return
        }

        accelerometerQueue.clear()
        activityBuffer.clear()
        recentPredictions.clear()
        isClassifying = true

        // Initialize gravity filter to zero
        gravity[0] = 0f
        gravity[1] = 0f
        gravity[2] = 0f

        // Register accelerometer listener
        val registered = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )

        android.util.Log.d(TAG, "Accelerometer registration: ${if (registered == true) "✓ SUCCESS" else "✗ FAILED"}")
        android.util.Log.d(TAG, "Using ${if (usingLinearAcceleration) "LINEAR_ACCELERATION" else "ACCELEROMETER with gravity filter"}")
        android.util.Log.d(TAG, "Noise threshold: $NOISE_THRESHOLD, Gravity alpha: $GRAVITY_FILTER_ALPHA")

        // Start classification thread
        classificationThread = thread {
            android.util.Log.d(TAG, "🧵 Classification thread started")
            while (isClassifying) {
                try {
                    val reading = accelerometerQueue.take()
                    processAccelerometerReading(reading)
                } catch (e: InterruptedException) {
                    android.util.Log.d(TAG, "🧵 Classification thread interrupted")
                    break
                }
            }
            android.util.Log.d(TAG, "🧵 Classification thread stopped")
        }
    }

    /**
     * Sensor callback - receives accelerometer data
     * IMPROVED: Better gravity filtering and noise threshold
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val isCorrectSensor = (usingLinearAcceleration && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) ||
                (!usingLinearAcceleration && event.sensor.type == Sensor.TYPE_ACCELEROMETER)

        if (isCorrectSensor) {
            var x = event.values[0]
            var y = event.values[1]
            var z = event.values[2]

            // If using regular accelerometer, remove gravity using improved low-pass filter
            if (!usingLinearAcceleration) {
                // High-pass filter: isolate gravity with stronger filtering
                gravity[0] = GRAVITY_FILTER_ALPHA * gravity[0] + (1 - GRAVITY_FILTER_ALPHA) * x
                gravity[1] = GRAVITY_FILTER_ALPHA * gravity[1] + (1 - GRAVITY_FILTER_ALPHA) * y
                gravity[2] = GRAVITY_FILTER_ALPHA * gravity[2] + (1 - GRAVITY_FILTER_ALPHA) * z

                // Remove gravity to get linear acceleration
                x -= gravity[0]
                y -= gravity[1]
                z -= gravity[2]
            }

            // Calculate magnitude
            val rawMagnitude = FeatureExtractor.calculateMagnitude(x, y, z)

            // Apply noise threshold to filter out very small movements
            val filteredMagnitude = if (rawMagnitude < NOISE_THRESHOLD) 0.0 else rawMagnitude

            sensorReadingCount++

            // Log every 100 readings for debugging
            if (sensorReadingCount % 100 == 0) {
                val sensorType = if (usingLinearAcceleration) "LINEAR" else "ACCEL"
                val gravityMag = if (!usingLinearAcceleration) {
                    " grav=${String.format("%.2f", FeatureExtractor.calculateMagnitude(gravity[0], gravity[1], gravity[2]))}"
                } else ""

                android.util.Log.d(TAG, "[$sensorType] raw=${String.format("%.3f", rawMagnitude)} " +
                        "filtered=${String.format("%.3f", filteredMagnitude)}$gravityMag " +
                        "queue=${accelerometerQueue.size} count=$sensorReadingCount")
            }

            // Add to queue for background processing
            try {
                accelerometerQueue.offer(filteredMagnitude)
            } catch (e: Exception) {
                // Queue full, skip reading
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

    /**
     * Processes accelerometer readings and performs classification
     * IMPROVED: Better logging and emulator handling
     */
    private fun processAccelerometerReading(magnitude: Double) {
        activityBuffer.add(magnitude)

        // Process when we have 64 readings (one block)
        if (activityBuffer.size >= FeatureExtractor.BLOCK_CAPACITY) {
            classificationCount++

            // Extract features from the block
            val block = activityBuffer.take(FeatureExtractor.BLOCK_CAPACITY).toDoubleArray()

            // Calculate some stats for debugging
            val avgMag = block.average()
            val maxMag = block.maxOrNull() ?: 0.0

            val features = FeatureExtractor.extractFeatures(block)

            // Classify - with emulator fallback
            val predictedActivity = if (runningOnEmulator) {
                // On emulator, use a simple heuristic based on average magnitude
                when {
                    avgMag < 0.5 -> 0  // Standing
                    avgMag < 2.0 -> 1  // Walking
                    else -> 2          // Running
                }
            } else {
                // Real device: use trained classifier
                activityClassifier?.classify(features) ?: 0
            }

            // Add to sliding window
            recentPredictions.add(predictedActivity)

            // Keep only last N predictions
            if (recentPredictions.size > PREDICTION_WINDOW_SIZE) {
                recentPredictions.removeAt(0)
            }

            // Determine overall activity using majority vote
            val activityCounts = recentPredictions.groupingBy { it }.eachCount()
            val dominantActivity = activityCounts.maxByOrNull { it.value }?.key ?: predictedActivity

            // Log classification results
            val classifierLabels = arrayOf("Standing", "Walking", "Running")
            val predictedLabel = classifierLabels.getOrNull(predictedActivity) ?: "Unknown"
            val dominantLabel = classifierLabels.getOrNull(dominantActivity) ?: "Unknown"

            android.util.Log.d(TAG, "🎯 Classification #$classificationCount: " +
                    "avgMag=${String.format("%.3f", avgMag)} " +
                    "maxMag=${String.format("%.3f", maxMag)} " +
                    "predicted=$predictedLabel " +
                    "window=$recentPredictions " +
                    "dominant=$dominantLabel" +
                    if (runningOnEmulator) " [EMULATOR MODE]" else "")

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

            android.util.Log.d(TAG, "✅ Activity updated: $activityLabel (mapped to index $mappedActivity)")

            // Remove processed readings from buffer
            repeat(FeatureExtractor.BLOCK_CAPACITY) {
                if (activityBuffer.isNotEmpty()) {
                    activityBuffer.removeAt(0)
                }
            }
        }
    }

    private fun stopActivityRecognition() {
        android.util.Log.d(TAG, "🛑 Stopping activity recognition")
        android.util.Log.d(TAG, "📊 Stats: $sensorReadingCount readings, $classificationCount classifications")
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
        android.util.Log.d(TAG, "🛑 Stopping tracking")

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
        android.util.Log.d(TAG, "🔚 Service destroyed")
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        stopActivityRecognition()
        super.onDestroy()
    }
}