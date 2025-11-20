package com.yadhuChoudhary.MyRuns5
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.launch

class MapDisplayActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
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
    private var currentMarker: Marker? = null

    private lateinit var repository: ExerciseRepository
    private var trackingService: TrackingService? = null
    private var isBound = false

    private var isLiveMode = false
    private var exerciseId: Long = -1
    private var currentExercise: ExerciseEntry? = null
    private var lastKnownSpeed = 0.0
    private var isManualEntry = false

    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 700L // 0.7 second updates for smoother map rendering

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)
        isLiveMode = exerciseId == -1L
        val inputType = intent.getIntExtra(Constants.EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_GPS)
        isManualEntry = inputType == Constants.INPUT_TYPE_MANUAL

        if (isManualEntry && !isLiveMode) {
            val intent = Intent(this, DisplayEntryActivity::class.java)
            intent.putExtra(Constants.EXTRA_EXERCISE_ID, exerciseId)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_map_display)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "MyRuns4"

        repository = ExerciseRepository(ExerciseDatabase.getDatabase(applicationContext).exerciseDao())

        btnSave = findViewById(R.id.btn_map_save)
        btnCancel = findViewById(R.id.btn_map_cancel)

        if (!isManualEntry) {
            val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            mapFragment.getMapAsync(this)
        } else {
            findViewById<ViewGroup>(R.id.map_container)?.visibility = View.GONE
        }

        createStatsOverlay()

        if (isLiveMode) {
            if (!isBound) {
                startLiveTracking()
            } else {
                bindToExistingService()
            }
        } else {
            setupHistoryMode()
        }

        setupButtons()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (!isLiveMode) {
            menuInflater.inflate(R.menu.menu_map_display, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_delete -> {
                deleteEntry()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun createStatsOverlay() {
        val rootLayout = if (isManualEntry) {
            findViewById<ViewGroup>(android.R.id.content)
        } else {
            findViewById<ViewGroup>(R.id.map_container)
        }

        statsOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#CC000000"))
            elevation = 8f
        }

        tvType = createStatTextView()
        tvAvgSpeed = createStatTextView()
        tvCurSpeed = createStatTextView()
        tvClimb = createStatTextView()
        tvCalorie = createStatTextView()
        tvDistance = createStatTextView()

        statsOverlayContainer.addView(tvType)
        statsOverlayContainer.addView(tvAvgSpeed)
        statsOverlayContainer.addView(tvCurSpeed)
        statsOverlayContainer.addView(tvClimb)
        statsOverlayContainer.addView(tvCalorie)
        statsOverlayContainer.addView(tvDistance)

        val params = if (isManualEntry) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(32, 32, 32, 32)
            }
        } else {
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

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = false
            isCompassEnabled = true
            isRotateGesturesEnabled = true
        }

        map.setMapType(GoogleMap.MAP_TYPE_NORMAL)

        if (checkPermission()) {
            try {
                map.isMyLocationEnabled = false
            } catch (e: SecurityException) {
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
        bindToExistingService()
    }

    private fun bindToExistingService() {
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
        btnSave.visibility = View.GONE
        btnCancel.visibility = View.GONE
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
        val type = Constants.ACTIVITY_TYPES[entry.activityType]

        val distStr = UnitConverter.formatDistance(entry.distance, this)

        val avgSpeed = UnitConverter.convertDistance(entry.avgSpeed, this)
        val curSpeed = UnitConverter.convertDistance(lastKnownSpeed, this)
        val speedUnit = UnitConverter.getDistanceUnitShort(this) + "/h"

        val avgStr = String.format("%.1f %s", avgSpeed, speedUnit)
        val curStr = String.format("%.1f %s", curSpeed, speedUnit)

        val climbStr = UnitConverter.formatClimb(entry.climb, this)
        val calStr = UnitConverter.formatCalories(entry.calorie)

        tvType.text = "Type: $type"
        tvAvgSpeed.text = "Avg speed: $avgStr"
        tvCurSpeed.text = "Cur speed: $curStr"
        tvClimb.text = "Climb: $climbStr"
        tvCalorie.text = "Calorie: $calStr"
        tvDistance.text = "Distance: $distStr"

        statsOverlayContainer.setBackgroundColor(
            if (isLiveMode) Color.parseColor("#CC000000")
            else Color.parseColor("#DD000000")
        )
    }

    private fun updateMapLive(entry: ExerciseEntry) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < UPDATE_INTERVAL_MS) {
            return
        }
        lastUpdateTime = currentTime

        val locationBytes = entry.locationList ?: return
        val list = LocationUtils.deserializeLocationList(locationBytes)
        if (list.size < 1) return

        // Add start marker
        if (startMarker == null && list.isNotEmpty()) {
            startMarker = googleMap?.addMarker(
                MarkerOptions().position(list.first())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title("Start")
            )
        }

        val last = list.last()

        // Update current position marker
        currentMarker?.remove()
        currentMarker = googleMap?.addMarker(
            MarkerOptions().position(last)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("Current Position")
        )

        // Update polyline
        polyline?.remove()
        polyline = googleMap?.addPolyline(
            PolylineOptions()
                .addAll(list)
                .color(Color.BLUE)
                .width(12f)
                .geodesic(true)
        )

        // Smoothly animate camera to current position
        googleMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(last, 18f),
            500,  // 500ms animation for smoother movement
            null
        )
    }

    private fun drawHistoryRoute(entry: ExerciseEntry) {
        val locationBytes = entry.locationList ?: return
        val list = LocationUtils.deserializeLocationList(locationBytes)
        if (list.isEmpty()) return

        // Both markers RED
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
            PolylineOptions()
                .addAll(list)
                .color(Color.BLUE)
                .width(12f)
                .geodesic(true)
        )

        if (list.size >= 2) {
            val builder = LatLngBounds.Builder()
            for (p in list) builder.include(p)
            val bounds = builder.build()
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200))
        } else if (list.size == 1) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(list.first(), 16f))
        }
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            // Finalize activity type based on which one was performed longest
            trackingService?.finalizeActivityType()

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

    private fun deleteEntry() {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Are you sure you want to delete this exercise entry?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    currentExercise?.let {
                        repository.delete(it)
                        Toast.makeText(this@MapDisplayActivity, "Entry deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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