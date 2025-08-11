package com.example.myapplication3

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : AppCompatActivity() {

    private lateinit var etRecordingDuration: EditText
    private lateinit var etDeletionTime: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure you have a layout file named R.layout.activity_settings
        // The content you provided for activity_settings.xml is correct.
        setContentView(R.layout.activity_settings)

        etRecordingDuration = findViewById(R.id.etRecordingDuration)
        etDeletionTime = findViewById(R.id.etDeletionTime)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)

        // Ensure AppSettings.kt exists in the same package and is correctly defined
        sharedPreferences = AppSettings.getSharedPreferences(this)

        loadSettings()

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val recordingDuration = sharedPreferences.getInt(
            AppSettings.KEY_RECORDING_DURATION_SEC,
            AppSettings.DEFAULT_RECORDING_DURATION_SEC
        )
        val deletionTime = sharedPreferences.getInt(
            AppSettings.KEY_DELETION_TIME_MIN,
            AppSettings.DEFAULT_DELETION_TIME_MIN
        )

        etRecordingDuration.setText(recordingDuration.toString())
        etDeletionTime.setText(deletionTime.toString())
    }

    private fun saveSettings() {
        val recordingDurationStr = etRecordingDuration.text.toString()
        val deletionTimeStr = etDeletionTime.text.toString()

        if (recordingDurationStr.isEmpty() || deletionTimeStr.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val recordingDuration = recordingDurationStr.toInt()
            val deletionTime = deletionTimeStr.toInt()

            if (recordingDuration <= 0 || deletionTime <= 0) {
                Toast.makeText(this, "Values must be greater than zero", Toast.LENGTH_SHORT).show()
                return
            }

            with(sharedPreferences.edit()) {
                putInt(AppSettings.KEY_RECORDING_DURATION_SEC, recordingDuration)
                putInt(AppSettings.KEY_DELETION_TIME_MIN, deletionTime)
                apply()
            }
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            finish() // Close the settings activity after saving

        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show()
        }
    }
}
