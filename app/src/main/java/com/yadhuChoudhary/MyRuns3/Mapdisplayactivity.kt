package com.yadhuChoudhary.MyRuns3

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.launch

class MapDisplayActivity : AppCompatActivity(), OnMapReadyCallback {

    // Buttons
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDelete: Button

    // Stats overlay views
    private lateinit var statsOverlayContainer: LinearLayout
    private lateinit var tvType: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvCurSpeed: TextView
    private lateinit var tvClimb: TextView
    private lateinit var tvCalorie: TextView
    private lateinit var tvDistance: TextView

    private var googleMap: GoogleMap? = null
    private var polyline: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null

    private lateinit var repository: ExerciseRepository
    private var trackingService: TrackingService? = null
    private var isBound = false

    private var isLiveMode = false
    private var exerciseId: Long = -1
    private var currentExercise: ExerciseEntry? = null

    private var lastKnownSpeed = 0.0
    private var hasZoomed = false

    private var isManualEntry = false
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_display)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Toolbar WITHOUT back arrow
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // DB
        repository = ExerciseRepository(
            ExerciseDatabase.getDatabase(applicationContext).exerciseDao()
        )

        btnSave = findViewById(R.id.btn_map_save)
        btnCancel = findViewById(R.id.btn_map_cancel)
        btnDelete = findViewById(R.id.btn_map_delete)

        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)
        isLiveMode = exerciseId == -1L

        // Check if this is manual entry
        val inputType = intent.getIntExtra(Constants.EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
        isManualEntry = inputType == Constants.INPUT_TYPE_MANUAL

        // Only setup map for GPS entries
        if (!isManualEntry) {
            val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
            mapFragment.getMapAsync(this)
        } else {
            // Hide map for manual entry
            findViewById<ViewGroup>(R.id.map_container)?.visibility = android.view.View.GONE
        }

        // Create stats overlay programmatically
        createStatsOverlay()

        if (isLiveMode) startLiveTracking()
        else setupHistoryMode()

        setupButtons()
    }

    private fun createStatsOverlay() {
        // Find the map container or use the root layout for manual entry
        val mapContainer = findViewById<ViewGroup>(R.id.map_container)
        val rootLayout = if (isManualEntry) {
            findViewById<ViewGroup>(android.R.id.content)
        } else {
            mapContainer
        }

        // Create the stats overlay container
        statsOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#CC000000"))
            elevation = 8f
        }

        // Create individual stat TextViews
        tvType = createStatTextView()
        tvAvgSpeed = createStatTextView()
        tvCurSpeed = createStatTextView()
        tvClimb = createStatTextView()
        tvCalorie = createStatTextView()
        tvDistance = createStatTextView()

        // Add all TextViews to container
        statsOverlayContainer.addView(tvType)
        statsOverlayContainer.addView(tvAvgSpeed)
        statsOverlayContainer.addView(tvCurSpeed)
        statsOverlayContainer.addView(tvClimb)
        statsOverlayContainer.addView(tvCalorie)
        statsOverlayContainer.addView(tvDistance)

        // Position the overlay
        val params = if (isManualEntry) {
            // Center on screen for manual entry
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(32, 32, 32, 32)
            }
        } else {
            // Top-left corner for map view
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(16, 16, 16, 16)
            }
        }

        rootLayout.addView(statsOverlayContainer, params)
    }

    private fun createStatTextView(): TextView {
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 8, 0, 8)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun deleteEntry() {
        lifecycleScope.launch {
            currentExercise?.let {
                repository.delete(it)
                Toast.makeText(this@MapDisplayActivity, "Deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = false
        }

        if (checkPermission()) {
            try {
                map.isMyLocationEnabled = false
            } catch (e: SecurityException) {
                // Permission issue, ignore
            }
        }

        if (!isLiveMode) loadHistoryEntry()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLiveTracking() {
        btnSave.isEnabled = true

        val inputType = intent.getIntExtra(Constants.EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
        val activityType = intent.getIntExtra(Constants.EXTRA_ACTIVITY_TYPE, 0)

        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            putExtra(TrackingService.EXTRA_INPUT_TYPE, inputType)
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, activityType)
        }

        startForegroundService(intent)

        bindService(
            Intent(this, TrackingService::class.java),
            connection, Context.BIND_AUTO_CREATE
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as TrackingService.LocalBinder
            trackingService = b.getService()
            isBound = true

            trackingService!!.exerciseEntryLiveData.observe(this@MapDisplayActivity) {
                updateStats(it)
                if (!isManualEntry) {
                    updateMapLive(it)
                }
            }

            // Observe current speed updates
            trackingService!!.currentSpeedLiveData.observe(this@MapDisplayActivity) { speed ->
                lastKnownSpeed = speed
                trackingService?.getCurrentEntry()?.let { entry ->
                    updateStats(entry)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private fun setupHistoryMode() {
        btnSave.visibility = android.view.View.GONE
        btnCancel.visibility = android.view.View.GONE
        btnDelete.visibility = android.view.View.VISIBLE

        // Make stats visible with semi-transparent background for history
        statsOverlayContainer.setBackgroundColor(Color.parseColor("#DD000000"))
    }

    private fun loadHistoryEntry() {
        lifecycleScope.launch {
            val entry = repository.getExerciseById(exerciseId)
            currentExercise = entry
            if (entry != null) {
                updateStats(entry)
                if (!isManualEntry && entry.inputType != Constants.INPUT_TYPE_MANUAL) {
                    drawHistoryRoute(entry)
                }
            }
        }
    }

    private fun updateStats(entry: ExerciseEntry) {
        // Get unit preference
        val unitPref = sharedPreferences.getInt(Constants.PREF_UNIT, Constants.UNIT_KILOMETERS)
        val isMiles = unitPref == Constants.UNIT_MILES

        val type = Constants.ACTIVITY_TYPES[entry.activityType]

        // Convert values based on unit preference
        val dist = if (isMiles) entry.distance * 0.621371 else entry.distance
        val avg = if (isMiles) entry.avgSpeed * 0.621371 else entry.avgSpeed
        val climb = if (isMiles) entry.climb * 0.621371 else entry.climb
        val cur = if (isMiles) lastKnownSpeed * 0.621371 else lastKnownSpeed

        val distStr = String.format("%.2f", dist)
        val avgStr = String.format("%.2f", avg)
        val climbStr = String.format("%.2f", climb)
        val calStr = String.format("%.0f", entry.calorie)
        val curStr = String.format("%.2f", cur)

        val unitLabel = if (isMiles) "miles" else "kilometers"
        val speedUnit = if (isMiles) "mph" else "km/h"

        tvType.text = "Type: $type"
        tvAvgSpeed.text = "Avg speed: $avgStr $speedUnit"
        tvCurSpeed.text = "Cur speed: $curStr $speedUnit"
        tvClimb.text = "Climb: $climbStr $unitLabel"
        tvCalorie.text = "Calorie: $calStr"
        tvDistance.text = "Distance: $distStr $unitLabel"

        // Update background color based on mode
        statsOverlayContainer.setBackgroundColor(
            if (isLiveMode) Color.parseColor("#CC000000")
            else Color.parseColor("#DD000000")
        )
    }

    private fun updateMapLive(entry: ExerciseEntry) {
        val locationBytes = entry.locationList ?: return
        val list = LocationUtils.deserializeLocationList(locationBytes)
        if (list.size < 1) return

        if (startMarker == null) {
            startMarker = googleMap?.addMarker(
                MarkerOptions().position(list.first())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title("Start")
            )
        }

        val last = list.last()

        endMarker?.remove()
        endMarker = googleMap?.addMarker(
            MarkerOptions().position(last)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("End")
        )

        polyline?.remove()
        polyline = googleMap?.addPolyline(
            PolylineOptions().addAll(list).color(Color.BLUE).width(10f)
        )

        if (!hasZoomed && list.size >= 2) {
            val builder = LatLngBounds.Builder()
            for (p in list) builder.include(p)
            val bounds = builder.build()
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            hasZoomed = true
        } else if (!hasZoomed && list.size == 1) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(last, 17f))
            hasZoomed = true
        }
    }

    private fun drawHistoryRoute(entry: ExerciseEntry) {
        val locationBytes = entry.locationList ?: return
        val list = LocationUtils.deserializeLocationList(locationBytes)
        if (list.isEmpty()) return

        startMarker = googleMap?.addMarker(
            MarkerOptions().position(list.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("Start")
        )

        endMarker = googleMap?.addMarker(
            MarkerOptions().position(list.last())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("End")
        )

        polyline = googleMap?.addPolyline(
            PolylineOptions().addAll(list).color(Color.BLUE).width(10f)
        )

        if (list.size >= 2) {
            val builder = LatLngBounds.Builder()
            for (p in list) builder.include(p)
            val bounds = builder.build()
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } else if (list.size == 1) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(list.first(), 17f))
        }
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            val entry = trackingService?.getCurrentEntry() ?: return@setOnClickListener
            lifecycleScope.launch {
                repository.insert(entry)
                stopTracking()
                finish()
            }
        }

        btnCancel.setOnClickListener {
            stopTracking()
            finish()
        }

        btnDelete.setOnClickListener {
            deleteEntry()
        }

        btnDelete.visibility = android.view.View.GONE
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java)
        intent.action = TrackingService.ACTION_STOP_TRACKING
        startService(intent)
    }

    override fun onDestroy() {
        if (isBound) unbindService(connection)
        super.onDestroy()
    }
}