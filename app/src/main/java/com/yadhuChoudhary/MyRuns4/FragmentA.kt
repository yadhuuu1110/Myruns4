package com.yadhuChoudhary.MyRuns4
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.fragment.app.Fragment

class FragmentA : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_a, container, false)
        val spinnerInput: Spinner = rootView.findViewById(R.id.spinner_input)
        val spinnerActivity: Spinner = rootView.findViewById(R.id.spinner_activity)
        val btnStart: Button = rootView.findViewById(R.id.btn_start)

        ArrayAdapter.createFromResource(
            requireContext(), R.array.input,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerInput.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.activity,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerActivity.adapter = adapter
        }

        btnStart.setOnClickListener {
            val inputType = spinnerInput.selectedItemPosition
            val activityType = spinnerActivity.selectedItemPosition

            if (inputType == Constants.INPUT_TYPE_MANUAL) {
                // Manual Entry - open ManualActivity
                val intent = Intent(requireContext(), ManualActivity::class.java)
                intent.putExtra(Constants.EXTRA_INPUT_TYPE, inputType)
                intent.putExtra(Constants.EXTRA_ACTIVITY_TYPE, activityType)
                startActivity(intent)
            } else {
                // GPS or Automatic - open MapDisplayActivity
                val intent = Intent(requireContext(), MapDisplayActivity::class.java)
                intent.putExtra(Constants.EXTRA_INPUT_TYPE, inputType)
                intent.putExtra(Constants.EXTRA_ACTIVITY_TYPE, activityType)
                startActivity(intent)
            }
        }

        return rootView
    }
}