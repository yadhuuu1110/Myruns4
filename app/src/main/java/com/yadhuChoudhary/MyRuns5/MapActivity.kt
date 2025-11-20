package com.yadhuChoudhary.MyRuns5
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MapActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.map_activity)
        supportActionBar?.title = "Map"

        val btnSave: Button = findViewById(R.id.btn_save)
        val btnCancel: Button = findViewById(R.id.btn_cancel)

        btnSave.setOnClickListener {
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }
}