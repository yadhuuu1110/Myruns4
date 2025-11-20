package com.yadhuChoudhary.MyRuns5
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ManualActivity : AppCompatActivity() {

    private lateinit var repository: ExerciseRepository
    private var currentEntry: ExerciseEntry = ExerciseEntry()
    private val dateFormat = SimpleDateFormat("HH:mm:ss MMM dd yyyy", Locale.getDefault())

    companion object {
        private const val KEY_INPUT_TYPE = "input_type"
        private const val KEY_ACTIVITY_TYPE = "activity_type"
        private const val KEY_DATE_TIME = "date_time"
        private const val KEY_DURATION = "duration"
        private const val KEY_DISTANCE = "distance"
        private const val KEY_CALORIES = "calories"
        private const val KEY_HEART_RATE = "heart_rate"
        private const val KEY_COMMENT = "comment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.manual_activity)

        // Initialize database
        val database = ExerciseDatabase.getDatabase(applicationContext)
        repository = ExerciseRepository(database.exerciseDao())

        val tvDate: TextView = findViewById(R.id.tv_date)
        val tvTime: TextView = findViewById(R.id.tv_time)
        val tvDuration: TextView = findViewById(R.id.tv_duration)
        val tvDistance: TextView = findViewById(R.id.tv_distance)
        val tvCalories: TextView = findViewById(R.id.tv_calories)
        val tvHeartRate: TextView = findViewById(R.id.tv_heart_rate)
        val tvComment: TextView = findViewById(R.id.tv_comment)
        val btnSave: Button = findViewById(R.id.btn_save)
        val btnCancel: Button = findViewById(R.id.btn_cancel)

        // Restore saved state or create new entry
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        } else {
            // Get input type and activity type from intent
            val inputType = intent.getIntExtra(Constants.EXTRA_INPUT_TYPE, Constants.INPUT_TYPE_MANUAL)
            val activityType = intent.getIntExtra(Constants.EXTRA_ACTIVITY_TYPE, 0)

            currentEntry.inputType = inputType
            currentEntry.activityType = activityType
            currentEntry.dateTime = Calendar.getInstance()
        }

        // Set initial display - just labels
        tvDate.text = "Date"
        tvTime.text = "Time"
        tvDuration.text = "Duration"
        tvDistance.text = "Distance"
        tvCalories.text = "Calories"
        tvHeartRate.text = "Heart Rate"
        tvComment.text = "Comment"

        tvDate.setOnClickListener {
            val cal = currentEntry.dateTime
            DatePickerDialog(this, { _, year, month, day ->
                cal.set(year, month, day)
                currentEntry.dateTime = cal
                // Keep the label as "Date"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        tvTime.setOnClickListener {
            val cal = currentEntry.dateTime
            TimePickerDialog(this, { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                currentEntry.dateTime = cal
                // Keep the label as "Time"
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        tvDuration.setOnClickListener {
            showNumberInputDialog("Duration", "") {
                val value = it.toDoubleOrNull() ?: 0.0
                currentEntry.duration = value * 60 // Convert minutes to seconds
                // Keep the label as "Duration"
            }
        }

        tvDistance.setOnClickListener {
            showNumberInputDialog("Distance", "") {
                val value = it.toDoubleOrNull() ?: 0.0
                currentEntry.distance = value
                // Keep the label as "Distance"
            }
        }

        tvCalories.setOnClickListener {
            showNumberInputDialog("Calories", "") {
                val value = it.toDoubleOrNull() ?: 0.0
                currentEntry.calorie = value
                // Keep the label as "Calories"
            }
        }

        tvHeartRate.setOnClickListener {
            showNumberInputDialog("Heart Rate", "") {
                val value = it.toDoubleOrNull() ?: 0.0
                currentEntry.heartRate = value
                // Keep the label as "Heart Rate"
            }
        }

        tvComment.setOnClickListener {
            showTextInputDialog("Comment", "") {
                currentEntry.comment = it
                // Keep the label as "Comment"
            }
        }

        btnSave.setOnClickListener {
            saveExerciseEntry()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Save all the current entry data
        outState.putInt(KEY_INPUT_TYPE, currentEntry.inputType)
        outState.putInt(KEY_ACTIVITY_TYPE, currentEntry.activityType)
        outState.putLong(KEY_DATE_TIME, currentEntry.dateTime.timeInMillis)
        outState.putDouble(KEY_DURATION, currentEntry.duration)
        outState.putDouble(KEY_DISTANCE, currentEntry.distance)
        outState.putDouble(KEY_CALORIES, currentEntry.calorie)
        outState.putDouble(KEY_HEART_RATE, currentEntry.heartRate)
        outState.putString(KEY_COMMENT, currentEntry.comment)
    }

    private fun restoreState(savedInstanceState: Bundle) {
        currentEntry.inputType = savedInstanceState.getInt(KEY_INPUT_TYPE, Constants.INPUT_TYPE_MANUAL)
        currentEntry.activityType = savedInstanceState.getInt(KEY_ACTIVITY_TYPE, 0)

        val dateTimeMillis = savedInstanceState.getLong(KEY_DATE_TIME, Calendar.getInstance().timeInMillis)
        currentEntry.dateTime = Calendar.getInstance().apply {
            timeInMillis = dateTimeMillis
        }

        currentEntry.duration = savedInstanceState.getDouble(KEY_DURATION, 0.0)
        currentEntry.distance = savedInstanceState.getDouble(KEY_DISTANCE, 0.0)
        currentEntry.calorie = savedInstanceState.getDouble(KEY_CALORIES, 0.0)
        currentEntry.heartRate = savedInstanceState.getDouble(KEY_HEART_RATE, 0.0)
        currentEntry.comment = savedInstanceState.getString(KEY_COMMENT, "")
    }

    private fun showNumberInputDialog(title: String, hint: String, onResult: (String) -> Unit) {
        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            this.hint = hint
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("OK") { _, _ -> onResult(editText.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextInputDialog(title: String, initialValue: String, onResult: (String) -> Unit) {
        val editText = EditText(this).apply {
            setText(initialValue)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("OK") { _, _ -> onResult(editText.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveExerciseEntry() {
        lifecycleScope.launch {
            try {
                repository.insert(currentEntry)
                Toast.makeText(
                    this@ManualActivity,
                    "Exercise saved successfully",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ManualActivity,
                    "Error saving exercise: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}