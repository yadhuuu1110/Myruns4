package com.yadhuChoudhary.MyRuns3

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val context: Context,
    private val onItemClick: (ExerciseEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var exercises = listOf<ExerciseEntry>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_entry_title)
        val tvDetails: TextView = view.findViewById(R.id.tv_entry_details)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = exercises[position]

        // Format title based on input type and activity type
        val inputTypeStr = when (entry.inputType) {
            Constants.INPUT_TYPE_MANUAL -> "Manual"
            Constants.INPUT_TYPE_GPS -> "GPS"
            Constants.INPUT_TYPE_AUTOMATIC -> "Automatic"
            else -> ""
        }

        val activityType = Constants.ACTIVITY_TYPES[entry.activityType]

        // Title format: "InputType: ActivityType, Time"
        val dateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        val timeStr = dateFormat.format(entry.dateTime.time)
        holder.tvTitle.text = "$inputTypeStr: $activityType, $timeStr"

        // Format details with unit conversion
        val dateOnlyFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
        val dateStr = dateOnlyFormat.format(entry.dateTime.time)

        val distanceStr = UnitConverter.formatDistance(entry.distance, context)
        val durationStr = UnitConverter.formatDuration(entry.duration)

        holder.tvDetails.text = "$dateStr\n$distanceStr, $durationStr"

        holder.itemView.setOnClickListener {
            onItemClick(entry)
        }
    }

    override fun getItemCount() = exercises.size

    fun submitList(newExercises: List<ExerciseEntry>) {
        exercises = newExercises
        notifyDataSetChanged()
    }
}