package com.yadhuChoudhary.MyRuns3

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class FragmentB : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnDeleteAll: Button  // NEW: Delete all button
    private lateinit var adapter: ExerciseAdapter
    private lateinit var repository: ExerciseRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_b, container, false)

        // Initialize repository
        repository = ExerciseRepository(
            ExerciseDatabase.getDatabase(requireContext()).exerciseDao()
        )

        // RecyclerView setup
        recyclerView = view.findViewById(R.id.recycler_view_history)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Delete All button setup
        btnDeleteAll = view.findViewById(R.id.btn_delete_all_records)
        btnDeleteAll.setOnClickListener {
            deleteAllRecords()
        }

        // Initialize adapter with click listener
        adapter = ExerciseAdapter { exercise ->
            openMapDisplay(exercise)
        }
        recyclerView.adapter = adapter

        // Load data
        loadExerciseHistory()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadExerciseHistory()
    }

    private fun loadExerciseHistory() {
        lifecycleScope.launch {
            val exercises = repository.getAllExercises()
            adapter.submitList(exercises)

            // Show/hide delete all button based on whether there are records
            btnDeleteAll.visibility = if (exercises.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun deleteAllRecords() {
        lifecycleScope.launch {
            repository.deleteAll()
            Toast.makeText(requireContext(), "All records deleted", Toast.LENGTH_SHORT).show()
            loadExerciseHistory()  // Refresh the list
        }
    }

    private fun openMapDisplay(exercise: ExerciseEntry) {
        val intent = Intent(requireContext(), MapDisplayActivity::class.java)
        intent.putExtra(Constants.EXTRA_EXERCISE_ID, exercise.id)
        startActivity(intent)
    }
}