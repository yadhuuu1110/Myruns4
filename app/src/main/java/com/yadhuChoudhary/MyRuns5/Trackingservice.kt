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

class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        private const val TAG = Globals.TAG

        private const val NOISE_THRESHOLD = 0.2
        private const val GRAVITY_FILTER_ALPHA = 0.99f
    }

    private val binder = LocalBinder()

    // Location tracking
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var usingLinearAcceleration = false
    private val accelerometerQueue = ArrayBlockingQueue<Double>(1024)
    private var classificationThread: Thread? = null
    private var isClassifying = false

    private val gravity = FloatArray(3)
    private var runningOnEmulator = false
    private var activityClassifier: ActivityClassifier? = null

    private val recentMagnitudes = ArrayDeque<Double>(3)
    private val MAGNITUDE_WINDOW_SIZE = 3
    private val STANDING_THRESHOLD = 0.5
    private val WALKING_THRESHOLD = 5.0

    private var sensorReadingCount = 0
    private var classificationCount = 0

    private var lastActivityChangeTime: Long = 0
    private var currentDetectedActivity: Int = Constants.ACTIVITY_TYPE_STANDING
    private val activityDurations = mutableMapOf<Int, Long>()

    private val _exerciseEntryLiveData = MutableLiveData<ExerciseEntry>()
    val exerciseEntryLiveData: LiveData<ExerciseEntry> = _exerciseEntryLiveData

    private val _currentSpeedLiveData = MutableLiveData<Double>()
    val currentSpeedLiveData: LiveData<Double> = _currentSpeedLiveData

    private val _detectedActivityLiveData = MutableLiveData<String>()
    val detectedActivityLiveData: LiveData<String> = _detectedActivityLiveData

    private var currentEntry: ExerciseEntry? = null
    private val locationList = mutableListOf<LatLng>()
    private var startTime: Long = 0
    private var totalDistance: Double = 0.0
    private var lastLocation: Location? = null
    private var maxAltitude: Double = 0.0
    private var minAltitude: Double = Double.MAX_VALUE
    private var inputType: Int = 1
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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer != null) {
            usingLinearAcceleration = true
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
                inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, 1)
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

        recentMagnitudes.clear()
        sensorReadingCount = 0
        classificationCount = 0
        activityDurations.clear()
        lastActivityChangeTime = System.currentTimeMillis()
        currentDetectedActivity = Constants.ACTIVITY_TYPE_STANDING

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

        if (inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            _detectedActivityLiveData.postValue("Standing")
            startActivityRecognition()
        }

        if (inputType == Constants.INPUT_TYPE_GPS || inputType == Constants.INPUT_TYPE_AUTOMATIC) {
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
        isClassifying = true

        // Initialize gravity filter to zero
        gravity[0] = 0f
        gravity[1] = 0f
        gravity[2] = 0f

        sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        classificationThread = thread {
            while (isClassifying) {
                try {
                    val reading = accelerometerQueue.take()
                    processAccelerometerReading(reading)
                } catch (e: InterruptedException) {
                    break
                }
            }
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

            if (!usingLinearAcceleration) {
                gravity[0] = GRAVITY_FILTER_ALPHA * gravity[0] + (1 - GRAVITY_FILTER_ALPHA) * x
                gravity[1] = GRAVITY_FILTER_ALPHA * gravity[1] + (1 - GRAVITY_FILTER_ALPHA) * y
                gravity[2] = GRAVITY_FILTER_ALPHA * gravity[2] + (1 - GRAVITY_FILTER_ALPHA) * z

                x -= gravity[0]
                y -= gravity[1]
                z -= gravity[2]
            }

            val rawMagnitude = FeatureExtractor.calculateMagnitude(x, y, z)
            val filteredMagnitude = if (rawMagnitude < NOISE_THRESHOLD) 0.0 else rawMagnitude

            sensorReadingCount++

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
    }

    private fun processAccelerometerReading(magnitude: Double) {
        if (recentMagnitudes.size >= MAGNITUDE_WINDOW_SIZE) {
            recentMagnitudes.removeFirst()
        }
        recentMagnitudes.addLast(magnitude)

        if (recentMagnitudes.size < MAGNITUDE_WINDOW_SIZE) return

        classificationCount++

        val avgMag = recentMagnitudes.average()

        val detectedActivity = when {
            avgMag < STANDING_THRESHOLD -> Constants.ACTIVITY_TYPE_STANDING
            avgMag < WALKING_THRESHOLD -> Constants.ACTIVITY_TYPE_WALKING
            else -> Constants.ACTIVITY_TYPE_RUNNING
        }

        val activityLabel = Constants.ACTIVITY_TYPES[detectedActivity]

        val currentTime = System.currentTimeMillis()
        if (detectedActivity != currentDetectedActivity) {
            val timeSpent = currentTime - lastActivityChangeTime
            activityDurations[currentDetectedActivity] =
                activityDurations.getOrDefault(currentDetectedActivity, 0L) + timeSpent

            currentDetectedActivity = detectedActivity
            lastActivityChangeTime = currentTime
        }

        _detectedActivityLiveData.postValue(activityLabel)
    }

    private fun stopActivityRecognition() {
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
            Constants.ACTIVITY_TYPE_STANDING -> 50.0
            Constants.ACTIVITY_TYPE_WALKING -> 80.0
            Constants.ACTIVITY_TYPE_RUNNING -> 100.0
            else -> 80.0
        }
        return distance * caloriesPerMile
    }

    fun getCurrentEntry(): ExerciseEntry? {
        return currentEntry
    }

    fun finalizeActivityType() {
        if (inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            val currentTime = System.currentTimeMillis()
            val finalTimeSpent = currentTime - lastActivityChangeTime
            activityDurations[currentDetectedActivity] =
                activityDurations.getOrDefault(currentDetectedActivity, 0L) + finalTimeSpent

            val dominantActivity = activityDurations.maxByOrNull { it.value }?.key

            if (dominantActivity != null) {
                currentEntry?.activityType = dominantActivity
                updateExerciseEntry()
            }
        }
    }

    private fun stopTracking() {
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
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        stopActivityRecognition()
        super.onDestroy()
    }
}