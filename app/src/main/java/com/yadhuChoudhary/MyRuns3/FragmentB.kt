package com.yadhuChoudhary.MyRuns3

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class FragmentB : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnDeleteAll: Button
    private lateinit var adapter: HistoryExerciseAdapter
    private lateinit var viewModel: ExerciseViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_b, container, false)

        recyclerView = view.findViewById(R.id.recycler_view_history)
        btnDeleteAll = view.findViewById(R.id.btn_delete_all_records)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Initialize adapter with click listener
        adapter = HistoryExerciseAdapter { exercise ->
            val intent = if (exercise.inputType == Constants.INPUT_TYPE_MANUAL) {
                Intent(requireContext(), DisplayEntryActivity::class.java)
            } else {
                Intent(requireContext(), MapDisplayActivity::class.java)
            }
            intent.putExtra(Constants.EXTRA_EXERCISE_ID, exercise.id)
            startActivity(intent)
        }

        recyclerView.adapter = adapter

        // Initialize ViewModel
        viewModel = ViewModelProvider(requireActivity())[ExerciseViewModel::class.java]

        // Observe database changes
        viewModel.allExercises.observe(viewLifecycleOwner) { exercises ->
            exercises?.let {
                adapter.submitList(it)
                // Show/hide delete all button based on list size
                btnDeleteAll.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        // Delete all button click
        btnDeleteAll.setOnClickListener {
            deleteAllRecords()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh adapter when returning to fragment (in case units changed)
        adapter.notifyDataSetChanged()
    }

    private fun deleteAllRecords() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete All Records")
            .setMessage("Are you sure you want to delete all exercise records?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteAll()
                android.widget.Toast.makeText(
                    requireContext(),
                    "All records deleted",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// RecyclerView Adapter - uses list_item_exercise.xml
class HistoryExerciseAdapter(
    private val onItemClick: (ExerciseEntry) -> Unit
) : RecyclerView.Adapter<HistoryExerciseAdapter.ExerciseViewHolder>() {

    private var exercises = listOf<ExerciseEntry>()
    private val dateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())

    inner class ExerciseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFirstRow: TextView = view.findViewById(R.id.tv_first_row)
        val tvSecondRow: TextView = view.findViewById(R.id.tv_second_row)

        init {
            view.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(exercises[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_exercise, parent, false)
        return ExerciseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        val exercise = exercises[position]
        val context = holder.itemView.context

        // First row: Input Type: Activity Type, Time
        val inputType = when (exercise.inputType) {
            Constants.INPUT_TYPE_MANUAL -> "Manual Entry"
            Constants.INPUT_TYPE_GPS -> "GPS"
            Constants.INPUT_TYPE_AUTOMATIC -> "Automatic"
            else -> "Unknown"
        }

        val activityType = if (exercise.activityType < Constants.ACTIVITY_TYPES.size) {
            Constants.ACTIVITY_TYPES[exercise.activityType]
        } else {
            "Unknown"
        }

        val timeStr = dateFormat.format(exercise.dateTime.time)
        holder.tvFirstRow.text = "$inputType: $activityType, $timeStr"

        // Second row: Date, Distance, Duration
        val dateStr = dateOnlyFormat.format(exercise.dateTime.time)

        // Convert distance based on user preference
        val distanceStr = UnitConverter.formatDistance(exercise.distance, context)
        val durationStr = UnitConverter.formatDuration(exercise.duration)

        holder.tvSecondRow.text = "$dateStr\n$distanceStr, $durationStr"
    }

    override fun getItemCount() = exercises.size

    fun submitList(newExercises: List<ExerciseEntry>) {
        exercises = newExercises
        notifyDataSetChanged()
    }
}