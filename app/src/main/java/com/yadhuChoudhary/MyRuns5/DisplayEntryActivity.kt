package com.yadhuChoudhary.MyRuns5
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DisplayEntryActivity : AppCompatActivity() {

    private lateinit var repository: ExerciseRepository
    private var exerciseId: Long = -1
    private var currentExercise: ExerciseEntry? = null

    private val dateFormat = SimpleDateFormat("h:mm:ss a dd-MMM-yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_entry)

        // Set title with back button
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "MyRuns4"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize database
        val database = ExerciseDatabase.getDatabase(applicationContext)
        repository = ExerciseRepository(database.exerciseDao())

        // Get exercise ID from intent
        exerciseId = intent.getLongExtra(Constants.EXTRA_EXERCISE_ID, -1)

        if (exerciseId != -1L) {
            loadExerciseEntry()
        }
    }

    private fun loadExerciseEntry() {
        lifecycleScope.launch {
            try {
                currentExercise = repository.getExerciseById(exerciseId)
                currentExercise?.let { exercise ->
                    displayExerciseEntry(exercise)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@DisplayEntryActivity,
                    "Error loading exercise: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun displayExerciseEntry(exercise: ExerciseEntry) {
        // Input Type
        val inputTypeStr = when (exercise.inputType) {
            Constants.INPUT_TYPE_MANUAL -> "Manual Entry"
            Constants.INPUT_TYPE_GPS -> "GPS"
            Constants.INPUT_TYPE_AUTOMATIC -> "Automatic"
            else -> "Unknown"
        }
        findTextView(R.id.tv_input_type_value)?.text = inputTypeStr

        // Activity Type
        val activityTypeStr = if (exercise.activityType < Constants.ACTIVITY_TYPES.size) {
            Constants.ACTIVITY_TYPES[exercise.activityType]
        } else {
            "Unknown"
        }
        findTextView(R.id.tv_activity_type_value)?.text = activityTypeStr

        // Date and Time
        findTextView(R.id.tv_date_time_value)?.text = dateFormat.format(exercise.dateTime.time)

        // Duration
        findTextView(R.id.tv_duration_value)?.text = UnitConverter.formatDuration(exercise.duration)

        // Distance (with unit conversion)
        findTextView(R.id.tv_distance_value)?.text = UnitConverter.formatDistance(exercise.distance, this)

        // Calories
        findTextView(R.id.tv_calories_value)?.text = UnitConverter.formatCalories(exercise.calorie)

        // Heart Rate
        findTextView(R.id.tv_heart_rate_value)?.text = "${exercise.heartRate.toInt()} bpm"

        // Comment
        findTextView(R.id.tv_comment_value)?.text = exercise.comment
    }

    private fun findTextView(id: Int): TextView? {
        return try {
            findViewById<TextView>(id)
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        // SHOW DELETE BUTTON FOR ALL ENTRY TYPES
        menuInflater.inflate(R.menu.menu_display_entry, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_delete -> {
                deleteExerciseEntry()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun deleteExerciseEntry() {
        AlertDialog.Builder(this)
            .setTitle("Delete Entry")
            .setMessage("Are you sure you want to delete this exercise entry?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        currentExercise?.let { exercise ->
                            repository.delete(exercise)
                            Toast.makeText(
                                this@DisplayEntryActivity,
                                "Exercise deleted successfully",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@DisplayEntryActivity,
                            "Error deleting exercise: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}