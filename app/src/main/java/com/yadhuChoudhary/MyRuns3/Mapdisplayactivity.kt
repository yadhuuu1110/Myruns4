package com.yadhuChoudhary.MyRuns3

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.launch

class MapDisplayActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
        private const val DEFAULT_ZOOM = 17f
    }

    private var googleMap: GoogleMap? = null
    private var polyline: Polyline? = null
    private var startMarker: Marker? = null
    private var currentMarker: Marker? = null

    // UI Elements
    private lateinit var tvTypeStats: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    // Service binding
    private var trackingService: TrackingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingService.LocalBinder
            trackingService = binder.getService()
            isBound = true

            // Observe exercise entry updates
            trackingService?.exerciseEntryLiveData?.observe(this@MapDisplayActivity) { entry ->
                updateUI(entry, isLiveMode)
                updateMap(entry)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    // Mode: live tracking or history display
    private var isLiveMode = false
    private var exerciseId: Long = -1
    private lateinit var repository: ExerciseRepository
    private var hasZoomedToLocation = false
    private var currentExercise: ExerciseEntry? = null
    private var lastKnownSpeed = 0.0 // mph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_display)

        supportActionBar?.title = "Map Display"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize repository
        val database = ExerciseDatabase.getDatabase(applicationContext)
        repository = ExerciseRepository(database.exerciseDao())

        initializeViews()

        // Determine mode
        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)
        isLiveMode = exerciseId == -1L

        // Setup map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (isLiveMode) {
            setupLiveTracking()
        } else {
            setupHistoryDisplay()
        }

        setupButtons()
    }

    private fun initializeViews() {
        tvTypeStats = findViewById(R.id.tv_type_stats)
        btnSave = findViewById(R.id.btn_map_save)
        btnCancel = findViewById(R.id.btn_map_cancel)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Only show delete option in history mode
        if (!isLiveMode) {
            menuInflater.inflate(R.menu.menu_display_entry, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                deleteExerciseEntry()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun deleteExerciseEntry() {
        AlertDialog.Builder(this)
            .setTitle("Delete Exercise")
            .setMessage("Are you sure you want to delete this exercise?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        currentExercise?.let { exercise ->
                            repository.delete(exercise)
                            Toast.makeText(
                                this@MapDisplayActivity,
                                "Exercise deleted successfully",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MapDisplayActivity,
                            "Error deleting exercise: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap?.apply {
            uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMyLocationButtonEnabled = true
            }
        }

        // Enable my location if permission granted
        if (checkLocationPermission()) {
            enableMyLocation()
        } else {
            requestLocationPermission()
        }

        if (!isLiveMode) {
            loadHistoryEntry()
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
                Toast.makeText(this, "Location permission granted. GPS tracking started!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Location permission required for GPS tracking", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap?.isMyLocationEnabled = true

            // Get last known location and zoom to it (only in live mode)
            if (isLiveMode && !hasZoomedToLocation) {
                val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        val currentLatLng = LatLng(it.latitude, it.longitude)
                        googleMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM)
                        )
                        hasZoomedToLocation = true
                    }
                }
            }
        }
    }

    private fun setupLiveTracking() {
        btnSave.isEnabled = true
        btnCancel.text = "Cancel"

        // Start tracking service
        val inputType = intent.getIntExtra(Constants.EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
        val activityType = intent.getIntExtra(Constants.EXTRA_ACTIVITY_TYPE, 0)

        val serviceIntent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            putExtra(TrackingService.EXTRA_INPUT_TYPE, inputType)
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, activityType)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to service
        bindService(
            Intent(this, TrackingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun setupHistoryDisplay() {
        btnSave.isEnabled = false
        btnSave.visibility = android.view.View.GONE
        btnCancel.visibility = android.view.View.GONE // Hide BACK button in history mode
    }

    private fun loadHistoryEntry() {
        lifecycleScope.launch {
            try {
                val entry = repository.getExerciseById(exerciseId)
                entry?.let {
                    currentExercise = it
                    updateUI(it, false)
                    displayHistoryRoute(it)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MapDisplayActivity,
                    "Error loading entry: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateUI(entry: ExerciseEntry, showCurrentSpeed: Boolean) {
        val activityType = if (entry.activityType < Constants.ACTIVITY_TYPES.size) {
            Constants.ACTIVITY_TYPES[entry.activityType]
        } else {
            "Unknown"
        }

        val distance = UnitConverter.formatDistance(entry.distance, this)

        val statsText = buildString {
            append("Type: $activityType\n")
            append("Avg speed: ${String.format("%.1f", entry.avgSpeed)} km/h\n")

            // Show current speed in live mode
            if (showCurrentSpeed) {
                if (lastKnownSpeed > 0.1) {
                    append("Cur speed: ${String.format("%.1f", lastKnownSpeed)} km/h\n")
                } else {
                    append("Cur speed: N/A\n")
                }
            }

            append("Climb: ${String.format("%.0f", entry.climb)} Kilometers\n")
            append("Calorie: ${String.format("%.1f", entry.calorie)}\n")
            append("Distance: $distance")
        }

        tvTypeStats.text = statsText
    }

    private fun updateMap(entry: ExerciseEntry) {
        entry.locationList?.let { byteArray ->
            if (byteArray.isEmpty()) {
                android.util.Log.d("MapDisplay", "Location byte array is empty")
                return
            }

            val locations = LocationUtils.deserializeLocationList(byteArray)
            android.util.Log.d("MapDisplay", "Deserializing locations: ${locations.size} points")

            if (locations.isEmpty()) {
                android.util.Log.d("MapDisplay", "No locations after deserialization")
                return
            }

            // Add GREEN start marker (only once)
            if (startMarker == null && locations.isNotEmpty()) {
                android.util.Log.d("MapDisplay", "Creating START marker at: ${locations.first()}")
                startMarker = googleMap?.addMarker(
                    MarkerOptions()
                        .position(locations.first())
                        .title("Start")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
            }

            // Add/Update RED current position marker
            if (locations.isNotEmpty()) {
                val currentPosition = locations.last()
                android.util.Log.d("MapDisplay", "Current position: $currentPosition")

                if (currentMarker == null) {
                    // Create marker for the first time
                    android.util.Log.d("MapDisplay", "Creating CURRENT marker at: $currentPosition")
                    currentMarker = googleMap?.addMarker(
                        MarkerOptions()
                            .position(currentPosition)
                            .title("Current")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )

                    // Zoom to current position initially
                    if (!hasZoomedToLocation) {
                        googleMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(currentPosition, DEFAULT_ZOOM)
                        )
                        hasZoomedToLocation = true
                        android.util.Log.d("MapDisplay", "Zoomed to current location")
                    }
                } else {
                    // Just update the position of existing marker
                    android.util.Log.d("MapDisplay", "Updating CURRENT marker position to: $currentPosition")
                    currentMarker?.position = currentPosition
                }
            }

            // Update polyline (BLUE LINE)
            if (locations.size >= 2) {
                polyline?.remove()
                polyline = googleMap?.addPolyline(
                    PolylineOptions()
                        .addAll(locations)
                        .color(Color.BLUE)
                        .width(10f)
                )
                android.util.Log.d("MapDisplay", "Drew polyline with ${locations.size} points")
            }

            // Calculate current speed if we have recent locations
            if (locations.size >= 2) {
                calculateCurrentSpeed(locations)
            }
        } ?: run {
            android.util.Log.d("MapDisplay", "Entry has no location list")
        }
    }

    private fun calculateCurrentSpeed(locations: List<LatLng>) {
        // Calculate speed from last two points
        if (locations.size < 2) {
            lastKnownSpeed = 0.0
            return
        }

        val lastLoc = locations[locations.size - 1]
        val prevLoc = locations[locations.size - 2]

        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            prevLoc.latitude,
            prevLoc.longitude,
            lastLoc.latitude,
            lastLoc.longitude,
            results
        )

        // Assume 1 second interval between points (from TrackingService)
        val distanceKm = results[0] / 1000.0 // Convert meters to km
        val timeHours = 1.0 / 3600.0 // 1 second in hours
        lastKnownSpeed = distanceKm / timeHours // km/h
    }

    private fun displayHistoryRoute(entry: ExerciseEntry) {
        entry.locationList?.let { byteArray ->
            val locations = LocationUtils.deserializeLocationList(byteArray)

            if (locations.isEmpty()) {
                Toast.makeText(this, "No GPS data available for this entry", Toast.LENGTH_SHORT).show()
                return
            }

            // Add start marker (GREEN)
            startMarker = googleMap?.addMarker(
                MarkerOptions()
                    .position(locations.first())
                    .title("Start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )

            // Add end marker (RED)
            if (locations.size > 1) {
                currentMarker = googleMap?.addMarker(
                    MarkerOptions()
                        .position(locations.last())
                        .title("End")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            }

            // Draw polyline (BLUE LINE)
            polyline = googleMap?.addPolyline(
                PolylineOptions()
                    .addAll(locations)
                    .color(Color.BLUE)
                    .width(10f)
            )

            // DON'T zoom - keep the default zoom level
            // Just center on the route without changing zoom
            if (locations.isNotEmpty()) {
                val centerPosition = locations.first() // Or could use middle point
                googleMap?.moveCamera(CameraUpdateFactory.newLatLng(centerPosition))
            }
        }
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            if (isLiveMode) {
                saveExercise()
            }
        }

        btnCancel.setOnClickListener {
            if (isLiveMode) {
                // Stop tracking without saving - no confirmation dialog
                stopTracking()
            }
            finish()
        }
    }

    private fun saveExercise() {
        trackingService?.getCurrentEntry()?.let { entry ->
            if (entry.distance == 0.0 && entry.duration == 0.0) {
                Toast.makeText(
                    this,
                    "No tracking data recorded yet. Please wait for GPS signal.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            lifecycleScope.launch {
                try {
                    repository.insert(entry)
                    Toast.makeText(
                        this@MapDisplayActivity,
                        "Exercise saved successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                    stopTracking()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MapDisplayActivity,
                        "Error saving: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun stopTracking() {
        // Stop tracking service
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP_TRACKING
        }
        startService(stopIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                // Service was already unbound
            }
            isBound = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isLiveMode) {
            stopTracking()
        }
        finish()
        return true
    }
}