package com.yadhuChoudhary.MyRuns3

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream

class MapDisplayActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
        private const val DEFAULT_ZOOM = 15f
    }

    private lateinit var googleMap: GoogleMap
    private var polyline: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null

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
                updateUI(entry)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_display)

        supportActionBar?.title = "Map Display"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize repository
        val database = ExerciseDatabase.getDatabase(applicationContext)
        repository = ExerciseRepository(database.exerciseDao())

        initializeViews()
        checkLocationPermission()

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

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
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
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
        }

        enableMyLocation()

        if (!isLiveMode) {
            loadHistoryEntry()
        }
    }

    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
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
        btnCancel.text = "Back"
    }

    private fun loadHistoryEntry() {
        lifecycleScope.launch {
            try {
                val entry = repository.getExerciseById(exerciseId)
                entry?.let {
                    updateUI(it)
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

    private fun updateUI(entry: ExerciseEntry) {
        val activityType = if (entry.activityType < Constants.ACTIVITY_TYPES.size) {
            Constants.ACTIVITY_TYPES[entry.activityType]
        } else {
            "Unknown"
        }

        val statsText = buildString {
            append("Type: $activityType\n")
            append("Avg Speed: ${String.format("%.2f", entry.avgSpeed)} mph\n")
            append("Cur Speed: ${String.format("%.2f", entry.avgSpeed)} mph\n")
            append("Distance: ${String.format("%.2f", entry.distance)} miles\n")
            append("Climb: ${String.format("%.0f", entry.climb)} feet\n")
            append("Calories: ${entry.calorie.toInt()} cals\n")
        }

        tvTypeStats.text = statsText
    }

    private fun updateMap(entry: ExerciseEntry) {
        entry.locationList?.let { byteArray ->
            val locations = deserializeLocationList(byteArray)

            if (locations.isEmpty()) return

            // Clear existing markers and polyline
            startMarker?.remove()
            endMarker?.remove()
            polyline?.remove()

            // Add start marker
            startMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(locations.first())
                    .title("Start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )

            // Add end marker
            if (locations.size > 1) {
                endMarker = googleMap.addMarker(
                    MarkerOptions()
                        .position(locations.last())
                        .title("End")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            }

            // Draw polyline
            polyline = googleMap.addPolyline(
                PolylineOptions()
                    .addAll(locations)
                    .color(Color.BLUE)
                    .width(10f)
            )

            // Move camera to show the entire route
            if (locations.size > 1) {
                val builder = LatLngBounds.Builder()
                locations.forEach { builder.include(it) }
                val bounds = builder.build()
                val padding = 100
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            } else {
                googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(locations.first(), DEFAULT_ZOOM)
                )
            }
        }
    }

    private fun displayHistoryRoute(entry: ExerciseEntry) {
        entry.locationList?.let { byteArray ->
            val locations = deserializeLocationList(byteArray)

            if (locations.isEmpty()) {
                Toast.makeText(this, "No location data available", Toast.LENGTH_SHORT).show()
                return
            }

            // Add start marker
            googleMap.addMarker(
                MarkerOptions()
                    .position(locations.first())
                    .title("Start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )

            // Add end marker
            if (locations.size > 1) {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(locations.last())
                        .title("End")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            }

            // Draw polyline
            googleMap.addPolyline(
                PolylineOptions()
                    .addAll(locations)
                    .color(Color.BLUE)
                    .width(10f)
            )

            // Fit bounds
            val builder = LatLngBounds.Builder()
            locations.forEach { builder.include(it) }
            val bounds = builder.build()
            val padding = 100
            googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        }
    }

    private fun deserializeLocationList(byteArray: ByteArray): List<LatLng> {
        return LocationUtils.deserializeLocationList(byteArray)
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            if (isLiveMode) {
                saveExercise()
            }
        }

        btnCancel.setOnClickListener {
            if (isLiveMode) {
                // Stop tracking without saving
                stopTracking()
            }
            finish()
        }
    }

    private fun saveExercise() {
        trackingService?.getCurrentEntry()?.let { entry ->
            lifecycleScope.launch {
                try {
                    repository.insert(entry)
                    Toast.makeText(
                        this@MapDisplayActivity,
                        "Exercise saved successfully",
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
            unbindService(serviceConnection)
            isBound = false
        }
    }
}