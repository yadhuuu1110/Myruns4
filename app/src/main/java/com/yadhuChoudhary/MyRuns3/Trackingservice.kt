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

    // Activity recognition
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val accelerometerQueue = ArrayBlockingQueue<Double>(1024)
    private val activityCounts = IntArray(3) // Standing, Walking, Running
    private var isAutoMode = false
    private var classifierThread: Thread? = null

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Setup location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
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
                startTracking(inputType, activityType)
            }
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun startTracking(inputType: Int, activityType: Int) {
        if (isTracking) return

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

        // Start location updates
        startLocationUpdates()

        // Start activity recognition if in automatic mode
        if (isAutoMode) {
            startActivityRecognition()
        }

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())

        _exerciseEntryLiveData.postValue(currentEntry)
    }

    private fun stopTracking() {
        if (!isTracking) return

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
        currentEntry.locationList = serializeLocationList(locationList)

        _exerciseEntryLiveData.postValue(currentEntry)

        // Stop foreground and service
        stopForeground(true)
        stopSelf()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 5000 // 5 seconds
            fastestInterval = 2000 // 2 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        locationList.add(latLng)

        // Calculate distance
        lastLocation?.let { last ->
            val distance = last.distanceTo(location) / 1609.34 // Convert meters to miles
            totalDistance += distance
        }
        lastLocation = location

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

        _exerciseEntryLiveData.postValue(currentEntry)
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
                    // Convert Float -> Double
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

                // Update activity type based on majority vote
                val dominantActivity = activityCounts.indices.maxByOrNull { activityCounts[it] } ?: 0
                currentEntry.activityType = when (dominantActivity) {
                    ACTIVITY_STANDING -> 2 // Standing index in Constants.ACTIVITY_TYPES
                    ACTIVITY_WALKING -> 1 // Walking
                    ACTIVITY_RUNNING -> 0 // Running
                    else -> 0
                }

                _exerciseEntryLiveData.postValue(currentEntry)

            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun classifyActivity(block: DoubleArray): Int {
        // Simple classification based on magnitude
        val max = block.maxOrNull() ?: 0.0

        // FFT would go here in production - simplified for now
        val avgMagnitude = block.average()

        return when {
            avgMagnitude < 2.0 -> ACTIVITY_STANDING
            avgMagnitude < 6.0 -> ACTIVITY_WALKING
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
    }

    private fun serializeLocationList(locations: List<LatLng>): ByteArray {
        return LocationUtils.serializeLocationList(locations)
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
        serviceJob.cancel()
        if (isTracking) {
            stopTracking()
        }
    }
}