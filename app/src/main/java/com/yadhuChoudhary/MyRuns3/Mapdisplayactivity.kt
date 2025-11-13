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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.launch

private val Unit.FrameLayout: Any

class MapDisplayActivity : AppCompatActivity(), OnMapReadyCallback {

    // Buttons
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDelete: Button  // NEW: Delete button on map

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_display)

        // Toolbar WITHOUT back arrow
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(false)  // CHANGED: No back arrow

        // DB
        repository = ExerciseRepository(
            ExerciseDatabase.getDatabase(applicationContext).exerciseDao()
        )

        btnSave = findViewById(R.id.btn_map_save)
        btnCancel = findViewById(R.id.btn_map_cancel)
        btnDelete = findViewById(R.id.btn_map_delete)  // NEW

        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)
        isLiveMode = exerciseId == -1L

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Create stats overlay programmatically
        createStatsOverlay()

        if (isLiveMode) startLiveTracking()
        else setupHistoryMode()

        setupButtons()
    }

    private fun createStatsOverlay() {
        // Find the map container
        val mapContainer = findViewById<ViewGroup>(R.id.map_container)

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

        // Position the overlay in top-left corner
        val params = ViewGroup.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 16, 16, 16)
        }

        mapContainer.addView(statsOverlayContainer, params)
    }

    private fun createStatTextView(): TextView {
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 4, 0, 4)
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
                updateMapLive(it)
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

        statsOverlayContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun loadHistoryEntry() {
        lifecycleScope.launch {
            val entry = repository.getExerciseById(exerciseId)
            currentExercise = entry
            if (entry != null) {
                updateStats(entry)
                drawHistoryRoute(entry)
            }
        }
    }

    private fun updateStats(entry: ExerciseEntry) {
        val type = Constants.ACTIVITY_TYPES[entry.activityType]
        val dist = String.format("%.1f", entry.distance)
        val avg = String.format("%.1f", entry.avgSpeed)
        val climb = String.format("%.1f", entry.climb)
        val cal = String.format("%.1f", entry.calorie)
        val cur = String.format("%.1f", lastKnownSpeed)

        tvType.text = "Type: $type"
        tvAvgSpeed.text = "Avg speed: $avg km/h"
        tvCurSpeed.text = "Cur speed: $cur km/h"
        tvClimb.text = "Climb: $climb Kilometers"
        tvCalorie.text = "Calorie: $cal"
        tvDistance.text = "Distance: $dist Kilometers"

        statsOverlayContainer.setBackgroundColor(
            if (isLiveMode) Color.parseColor("#CC000000") else Color.TRANSPARENT
        )
    }

    private fun updateMapLive(entry: ExerciseEntry) {
        val list = LocationUtils.deserializeLocationList(entry.locationList ?: return)
        if (list.size < 1) return

        if (startMarker == null) {
            startMarker = googleMap?.addMarker(
                MarkerOptions().position(list.first())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
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
        val list = LocationUtils.deserializeLocationList(entry.locationList ?: return)
        if (list.isEmpty()) return

        startMarker = googleMap?.addMarker(
            MarkerOptions().position(list.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
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