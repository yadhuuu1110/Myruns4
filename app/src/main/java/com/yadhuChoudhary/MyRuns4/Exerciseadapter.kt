package com.yadhuChoudhary.MyRuns4
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ExerciseAdapter(
    private val onItemClick: (ExerciseEntry) -> Unit
) : ListAdapter<ExerciseEntry, ExerciseAdapter.ExerciseViewHolder>(ExerciseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exercise, parent, false)
        return ExerciseViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ExerciseViewHolder(
        itemView: View,
        private val onItemClick: (ExerciseEntry) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvActivityInfo: TextView = itemView.findViewById(R.id.tv_activity_info)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tv_date_time)
        private val tvDistanceTime: TextView = itemView.findViewById(R.id.tv_distance_time)
        private val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)

        fun bind(exercise: ExerciseEntry) {
            // Format: "GPS: Running, 8:59:44 PM"
            val inputType = if (exercise.inputType == Constants.INPUT_TYPE_GPS) "GPS" else "Manual"
            val activityType = Constants.ACTIVITY_TYPES[exercise.activityType]
            val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            val time = timeFormat.format(exercise.dateTime.time)

            tvActivityInfo.text = "$inputType: $activityType, $time"

            // Format: "12-Dec-2025"
            val dateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
            tvDateTime.text = dateFormat.format(exercise.dateTime.time)

            // Format: "0.0 Kilometers, 1.0mins"
            val distance = String.format("%.1f", exercise.distance)
            val duration = String.format("%.1f", exercise.duration / 60) // Convert seconds to minutes
            tvDistanceTime.text = "$distance Kilometers, ${duration}mins"

            // Format: "0.419999999999937secs"
            tvDuration.text = "${String.format("%.1f", exercise.duration)}secs"

            // Click listener
            itemView.setOnClickListener {
                onItemClick(exercise)
            }
        }
    }

    class ExerciseDiffCallback : DiffUtil.ItemCallback<ExerciseEntry>() {
        override fun areItemsTheSame(oldItem: ExerciseEntry, newItem: ExerciseEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ExerciseEntry, newItem: ExerciseEntry): Boolean {
            return oldItem == newItem
        }
    }
}