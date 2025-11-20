package com.yadhuChoudhary.MyRuns4
import android.app.*
import android.content.Context
import android.content.Intent
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

class TrackingService : Service() {

    companion object {
        const val ACTION_START_TRACKING = "START_TRACKING"
        const val ACTION_STOP_TRACKING = "STOP_TRACKING"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
    }

    private val binder = LocalBinder()
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // LiveData for exercise entry
    private val _exerciseEntryLiveData = MutableLiveData<ExerciseEntry>()
    val exerciseEntryLiveData: LiveData<ExerciseEntry> = _exerciseEntryLiveData

    // LiveData for current speed
    private val _currentSpeedLiveData = MutableLiveData<Double>()
    val currentSpeedLiveData: LiveData<Double> = _currentSpeedLiveData

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

        // Initialize current speed to 0
        _currentSpeedLiveData.postValue(0.0)

        // Create initial entry with Calendar
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

        if (inputType == Constants.INPUT_TYPE_GPS || inputType == Constants.INPUT_TYPE_AUTOMATIC) {
            // Get last known location immediately to reduce startup delay
            getLastKnownLocation()
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 1000 // 1 second
            fastestInterval = 1000 // 1 second
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 3f // Only update if moved 3 meters
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
            // Handle permission error
        }
    }

    private fun getLastKnownLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                location?.let {
                    // Use last known location as starting point
                    onLocationUpdate(it)
                }
            }
        } catch (e: SecurityException) {
            // Handle permission error
        }
    }

    private fun onLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        locationList.add(latLng)

        // Mark that we've received at least one location
        isFirstLocationReceived = true

        // Calculate current speed
        val currentSpeed = if (location.hasSpeed()) {
            // Convert m/s to mph
            (location.speed * 2.23694).coerceAtLeast(0.0)
        } else if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location) / 1609.34 // miles
            val timeDiff = (location.time - lastLocation!!.time) / 1000.0 / 3600.0 // hours
            if (timeDiff > 0) {
                (distance / timeDiff).coerceAtLeast(0.0)
            } else {
                0.0
            }
        } else {
            0.0
        }

        // Update current speed LiveData
        _currentSpeedLiveData.postValue(currentSpeed)

        // Calculate distance in miles
        if (lastLocation != null) {
            val distanceMeters = lastLocation!!.distanceTo(location)
            totalDistance += distanceMeters / 1609.34 // Convert to miles
        }

        // Track altitude for climb calculation
        if (location.hasAltitude()) {
            val altitude = location.altitude * 3.28084 // Convert to feet
            if (altitude > maxAltitude) maxAltitude = altitude
            if (altitude < minAltitude) minAltitude = altitude
        }

        lastLocation = location

        // Update entry
        updateExerciseEntry()
    }

    private fun updateExerciseEntry() {
        val currentTime = System.currentTimeMillis()
        val duration = (currentTime - startTime) / 1000.0 // seconds

        // Calculate average speed in mph
        val avgSpeed = if (duration > 0) {
            (totalDistance / (duration / 3600.0)) // mph
        } else {
            0.0
        }

        // Calculate climb in feet
        val climb = if (maxAltitude > 0 && minAltitude < Double.MAX_VALUE) {
            maxAltitude - minAltitude
        } else {
            0.0
        }

        // Calculate calories
        val calorie = calculateCalories(totalDistance, duration, activityType)

        // Create Calendar for dateTime
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startTime

        // Serialize location list to ByteArray
        val locationListBytes = if (locationList.isNotEmpty()) {
            LocationUtils.serializeLocationList(locationList)
        } else {
            null
        }

        currentEntry = ExerciseEntry(
            id = currentEntry?.id ?: 0,
            inputType = inputType,
            activityType = activityType,
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
        // Calories per mile
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
            else -> 80.0 // Other
        }

        return distance * caloriesPerMile
    }

    fun getCurrentEntry(): ExerciseEntry? {
        return currentEntry
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }

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
            .setContentTitle("MyRuns Tracking")
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
        super.onDestroy()
    }
}