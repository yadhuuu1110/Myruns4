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

    private lateinit var tvTypeStats: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

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

        // Toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // DB
        repository = ExerciseRepository(
            ExerciseDatabase.getDatabase(applicationContext).exerciseDao()
        )

        tvTypeStats = findViewById(R.id.tv_type_stats)
        btnSave = findViewById(R.id.btn_map_save)
        btnCancel = findViewById(R.id.btn_map_cancel)

        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)
        isLiveMode = exerciseId == -1L

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (isLiveMode) startLiveTracking()
        else setupHistoryMode()

        setupButtons()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (!isLiveMode) menuInflater.inflate(R.menu.menu_display_entry, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -----------------------------
    // DELETE ENTRY
    // -----------------------------
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Are you sure?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    currentExercise?.let {
                        repository.delete(it)
                        Toast.makeText(this@MapDisplayActivity, "Deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -----------------------------
    // MAP READY
    // -----------------------------
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
        }

        if (checkPermission()) map.isMyLocationEnabled = true
        else requestPermission()

        if (!isLiveMode) loadHistoryEntry()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            100
        )
    }

    // -----------------------------
    // LIVE MODE
    // -----------------------------
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

    // -----------------------------
    // HISTORY MODE
    // -----------------------------
    private fun setupHistoryMode() {
        btnSave.visibility = android.view.View.GONE
        btnCancel.visibility = android.view.View.GONE
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

    // -----------------------------
    // STAT FORMATTING
    // -----------------------------
    private fun updateStats(entry: ExerciseEntry) {
        val type = Constants.ACTIVITY_TYPES[entry.activityType]
        val dist = String.format("%.1f", entry.distance)
        val avg = String.format("%.1f", entry.avgSpeed)
        val climb = String.format("%.1f", entry.climb)
        val cal = String.format("%.1f", entry.calorie)
        val cur = String.format("%.1f", lastKnownSpeed)

        val stats = """
            Type: $type
            Avg speed: $avg km/h
            Cur speed: $cur km/h
            Climb: $climb Kilometers
            Calorie: $cal
            Distance: $dist Kilometers
        """.trimIndent()

        // black only in live mode
        tvTypeStats.setBackgroundColor(
            if (isLiveMode) Color.parseColor("#99000000") else Color.TRANSPARENT
        )

        tvTypeStats.text = stats
    }

    // -----------------------------
    // LIVE MODE MAP
    // -----------------------------
    private fun updateMapLive(entry: ExerciseEntry) {
        val list = LocationUtils.deserializeLocationList(entry.locationList ?: return)
        if (list.size < 1) return

        if (startMarker == null)
            startMarker = googleMap?.addMarker(
                MarkerOptions().position(list.first())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )

        val last = list.last()

        endMarker?.remove()
        endMarker = googleMap?.addMarker(
            MarkerOptions().position(last)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        polyline?.remove()
        polyline = googleMap?.addPolyline(
            PolylineOptions().addAll(list).color(Color.BLUE).width(10f)
        )

        if (!hasZoomed) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(last, 17f))
            hasZoomed = true
        }
    }

    // -----------------------------
    // HISTORY MAP
    // -----------------------------
    private fun drawHistoryRoute(entry: ExerciseEntry) {
        val list = LocationUtils.deserializeLocationList(entry.locationList ?: return)
        if (list.isEmpty()) return

        startMarker = googleMap?.addMarker(
            MarkerOptions().position(list.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )

        endMarker = googleMap?.addMarker(
            MarkerOptions().position(list.last())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        polyline = googleMap?.addPolyline(
            PolylineOptions().addAll(list).color(Color.BLUE).width(7f)
        )

        // AUTO FIT PERFECT ZOOM
        val builder = LatLngBounds.Builder()
        for (p in list) builder.include(p)

        val bounds = builder.build()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
    }

    // -----------------------------
    // BUTTONS
    // -----------------------------
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
