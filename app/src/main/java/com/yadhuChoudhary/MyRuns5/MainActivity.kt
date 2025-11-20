package com.yadhuChoudhary.MyRuns5
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var fragmentA: FragmentA
    private lateinit var fragmentB: FragmentB
    private lateinit var fragmentC: FragmentC
    private lateinit var viewPager2: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: Toolbar
    private lateinit var myAdapter: MyFragmentStateAdapter
    private lateinit var tabLayoutMediator: TabLayoutMediator

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    private val tabTitles = arrayOf("START", "HISTORY", "SETTINGS")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup toolbar with title
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "MyRuns5"

        fragmentA = FragmentA()
        fragmentB = FragmentB()
        fragmentC = FragmentC()

        val fragments = arrayListOf<Fragment>(fragmentA, fragmentB, fragmentC)

        viewPager2 = findViewById(R.id.viewpager)
        tabLayout = findViewById(R.id.tab)

        myAdapter = MyFragmentStateAdapter(this, fragments)
        viewPager2.adapter = myAdapter
        viewPager2.offscreenPageLimit = 2

        tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            tab.text = tabTitles[position]
        }
        tabLayoutMediator.attach()

        setupPermissionLauncher()
        requestAllPermissions()
    }

    private fun setupPermissionLauncher() {
        requestPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val denied = permissions.filter { !it.value }.keys
                if (denied.isEmpty()) {
                    Toast.makeText(this, "All permissions granted.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Some permissions denied: ${denied.joinToString()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun requestAllPermissions() {
        val required = mutableListOf<String>()

        // Location - ALWAYS REQUIRED at startup
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
            required.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Android 14+ foreground service location
        if (Build.VERSION.SDK_INT >= 34 &&
            !hasPermission(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        ) {
            required.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        // Foreground service
        if (!hasPermission(Manifest.permission.FOREGROUND_SERVICE)) {
            required.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Storage / media - ALWAYS REQUIRED at startup
        required += getStoragePermissions().filter { !hasPermission(it) }

        // REMOVED CAMERA FROM STARTUP - will be requested when user clicks camera button

        if (required.isNotEmpty()) {
            requestPermissionsLauncher.launch(required.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getStoragePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tabLayoutMediator.detach()
    }
}