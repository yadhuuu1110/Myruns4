package com.yadhuChoudhary.MyRuns3

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.collections.ArrayList
import kotlin.math.sqrt

class TrackingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "TrackingService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "TrackingServiceChannel"

        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"

        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"

        // Accelerometer constants
        const val ACCELEROMETER_BLOCK_CAPACITY = 64
        const val FEATURE_SIZE = ACCELEROMETER_BLOCK_CAPACITY + 1

        // Activity labels
        const val ACTIVITY_STANDING = 0
        const val ACTIVITY_WALKING = 1
        const val ACTIVITY_RUNNING = 2
    }

    // Binder for activity binding
    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Location tracking
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val locationList = ArrayList<LatLng>()

    // Exercise data
    private var currentEntry: ExerciseEntry = ExerciseEntry()
    private val _exerciseEntryLiveData = MutableLiveData<ExerciseEntry>()
    val exerciseEntryLiveData: LiveData<ExerciseEntry> = _exerciseEntryLiveData

    // Tracking state
    private var isTracking = false
    private var startTime: Long = 0
    private var totalDistance: Double = 0.0
    private var lastLocation: Location? = null

    // Activity recognition - FIXED
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val accelerometerQueue = ArrayBlockingQueue<Double>(1024)
    private val activityCounts = IntArray(3) // Standing, Walking, Running
    private var isAutoMode = false
    private var classifierThread: Thread? = null
    private var classificationCount = 0
    private val CLASSIFICATION_WINDOW = 10 // Update activity after 10 classifications

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Setup location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "Location update received: ${location.latitude}, ${location.longitude}")
                    handleLocationUpdate(location)
                }
            }
        }

        // Setup sensor manager for activity recognition
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
                val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, 0)
                Log.d(TAG, "Starting tracking - Input: $inputType, Activity: $activityType")
                startTracking(inputType, activityType)
            }
            ACTION_STOP_TRACKING -> {
                Log.d(TAG, "Stopping tracking")
                stopTracking()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind")
        return binder
    }

    private fun startTracking(inputType: Int, activityType: Int) {
        if (isTracking) {
            Log.d(TAG, "Already tracking, ignoring start request")
            return
        }

        Log.d(TAG, "Starting tracking sequence")
        isTracking = true
        isAutoMode = inputType == Constants.INPUT_TYPE_AUTOMATIC

        // Initialize exercise entry
        currentEntry = ExerciseEntry().apply {
            this.inputType = inputType
            this.activityType = activityType
            this.dateTime = Calendar.getInstance()
        }

        startTime = System.currentTimeMillis()
        totalDistance = 0.0
        locationList.clear()
        activityCounts.fill(0)
        classificationCount = 0

        // Start location updates
        startLocationUpdates()

        // Start activity recognition if in automatic mode
        if (isAutoMode) {
            startActivityRecognition()
        }

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Post initial entry
        _exerciseEntryLiveData.postValue(currentEntry)
        Log.d(TAG, "Tracking started successfully")
    }

    private fun stopTracking() {
        if (!isTracking) return

        Log.d(TAG, "Stopping tracking")
        isTracking = false

        // Stop location updates
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // Stop activity recognition
        if (isAutoMode) {
            stopActivityRecognition()
        }

        // Calculate final statistics
        calculateFinalStatistics()

        // Save location list to entry
        if (locationList.isNotEmpty()) {
            currentEntry.locationList = LocationUtils.serializeLocationList(locationList)
            Log.d(TAG, "Saved ${locationList.size} locations to entry")
        } else {
            Log.w(TAG, "No locations to save!")
        }

        _exerciseEntryLiveData.postValue(currentEntry)

        // Stop foreground and service
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "Tracking stopped")
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 1000 // 1 second - very frequent updates for accurate path
            fastestInterval = 500 // 0.5 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 1f // Update even for small movements (1 meter)
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Starting location updates with 1-second interval")
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Try to get last known location immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    Log.d(TAG, "Got last known location: ${it.latitude}, ${it.longitude}")
                    handleLocationUpdate(it)
                }
            }
        } else {
            Log.e(TAG, "Location permission not granted!")
        }
    }

    private fun handleLocationUpdate(location: Location) {
        // Filter out locations with poor accuracy (more than 20 meters)
        if (location.accuracy > 20f) {
            Log.d(TAG, "Skipping location with poor accuracy: ${location.accuracy}m")
            return
        }

        val latLng = LatLng(location.latitude, location.longitude)

        // For the first location, just add it
        if (locationList.isEmpty()) {
            locationList.add(latLng)
            Log.d(TAG, "Added first location: $latLng")
            lastLocation = location
        } else {
            // Calculate distance from last location
            lastLocation?.let { last ->
                val results = FloatArray(1)
                Location.distanceBetween(
                    last.latitude,
                    last.longitude,
                    location.latitude,
                    location.longitude,
                    results
                )
                val distanceMeters = results[0]
                val distanceMiles = distanceMeters / 1609.34

                // Only add if:
                // 1. Moved at least 2 meters (reduces GPS jitter)
                // 2. Movement is realistic (less than 100 mph = 0.028 miles per second)
                if (distanceMeters >= 2f && distanceMiles < 0.028) {
                    locationList.add(latLng)
                    totalDistance += distanceMiles
                    lastLocation = location
                    Log.d(TAG, "Location added. Distance: +$distanceMiles mi, Total: $totalDistance mi, Count: ${locationList.size}")
                } else if (distanceMeters < 2f) {
                    // Still update last location for accurate current speed
                    lastLocation = location
                    Log.d(TAG, "Location too close, not adding to path (${distanceMeters}m)")
                } else {
                    Log.d(TAG, "Location movement too fast, skipping ($distanceMiles miles)")
                }
            }
        }

        // Update exercise entry
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        currentEntry.duration = duration
        currentEntry.distance = totalDistance

        if (duration > 0) {
            currentEntry.avgSpeed = (totalDistance / duration) * 3600 // mph
            currentEntry.avgPace = if (totalDistance > 0) duration / totalDistance / 60 else 0.0
        }

        // Estimate calories (simple formula: 0.75 * weight * distance)
        currentEntry.calorie = 0.75 * 150 * totalDistance // Assuming 150 lbs

        // Update climb based on altitude changes
        if (location.hasAltitude() && lastLocation?.hasAltitude() == true) {
            val altitudeChange = location.altitude - lastLocation!!.altitude
            if (altitudeChange > 0) {
                currentEntry.climb += altitudeChange * 3.28084 // Convert meters to feet
            }
        }

        // Update location list in entry for real-time display
        currentEntry.locationList = LocationUtils.serializeLocationList(locationList)

        // Post update
        _exerciseEntryLiveData.postValue(currentEntry)
        Log.d(TAG, "Posted entry update - Distance: $totalDistance, Duration: $duration, Locations: ${locationList.size}")
    }

    private fun startActivityRecognition() {
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }

        // Start classifier thread
        classifierThread = Thread {
            runActivityClassifier()
        }.apply { start() }
    }

    private fun stopActivityRecognition() {
        sensorManager.unregisterListener(this)
        classifierThread?.interrupt()
        classifierThread = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {

                val magnitude = sqrt(
                    it.values[0] * it.values[0] +
                            it.values[1] * it.values[1] +
                            it.values[2] * it.values[2]
                )

                try {
                    accelerometerQueue.add(magnitude.toDouble())
                } catch (e: IllegalStateException) {
                    // Queue full → remove and insert again
                    accelerometerQueue.poll()
                    accelerometerQueue.add(magnitude.toDouble())
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun runActivityClassifier() {
        val block = DoubleArray(ACCELEROMETER_BLOCK_CAPACITY)

        while (!Thread.currentThread().isInterrupted && isTracking) {
            try {
                // Collect 64 readings
                for (i in block.indices) {
                    block[i] = accelerometerQueue.take()
                }

                // Classify activity
                val activityLabel = classifyActivity(block)
                activityCounts[activityLabel]++
                classificationCount++

                // Update activity type based on majority vote after CLASSIFICATION_WINDOW classifications
                if (classificationCount >= CLASSIFICATION_WINDOW) {
                    val dominantActivity = activityCounts.indices.maxByOrNull { activityCounts[it] } ?: 0

                    // Map to Constants.ACTIVITY_TYPES indices
                    currentEntry.activityType = when (dominantActivity) {
                        ACTIVITY_RUNNING -> 0    // Running
                        ACTIVITY_WALKING -> 1    // Walking
                        ACTIVITY_STANDING -> 2   // Standing
                        else -> 0
                    }

                    Log.d(TAG, "Activity updated: Counts[${activityCounts.joinToString()}] -> ${Constants.ACTIVITY_TYPES[currentEntry.activityType]}")

                    _exerciseEntryLiveData.postValue(currentEntry)

                    // Reset counters but keep some history
                    activityCounts[0] = activityCounts[0] / 2
                    activityCounts[1] = activityCounts[1] / 2
                    activityCounts[2] = activityCounts[2] / 2
                    classificationCount = 0
                }

            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun classifyActivity(block: DoubleArray): Int {
        // Improved classification based on statistical features
        val max = block.maxOrNull() ?: 0.0
        val avgMagnitude = block.average()
        val variance = block.map { (it - avgMagnitude) * (it - avgMagnitude) }.average()
        val stdDev = sqrt(variance)

        // More refined thresholds
        return when {
            avgMagnitude < 1.5 && stdDev < 1.0 -> ACTIVITY_STANDING
            avgMagnitude < 4.0 && max < 8.0 -> ACTIVITY_WALKING
            else -> ACTIVITY_RUNNING
        }
    }

    private fun calculateFinalStatistics() {
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        currentEntry.duration = duration
        currentEntry.distance = totalDistance

        if (duration > 0 && totalDistance > 0) {
            currentEntry.avgSpeed = (totalDistance / duration) * 3600
            currentEntry.avgPace = duration / totalDistance / 60
        }

        Log.d(TAG, "Final stats - Distance: $totalDistance, Duration: $duration, Locations: ${locationList.size}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Exercise tracking notification"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyRuns Tracking")
            .setContentText("Recording your workout...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun getCurrentEntry(): ExerciseEntry {
        return currentEntry
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceJob.cancel()
        if (isTracking) {
            stopTracking()
        }
    }
}