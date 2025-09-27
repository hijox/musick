package com.example.musick

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var randomStartSwitch: SwitchMaterial
    private lateinit var backButton: FloatingActionButton
    private lateinit var preferences: SharedPreferences

    companion object {
        const val PREFS_NAME = "musick_settings"
        const val PREF_RANDOM_START = "random_song_start"

        // Helper function to get random start setting from anywhere in the app
        fun isRandomStartEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(PREF_RANDOM_START, false)
            Log.d("SettingsActivity", "Random start setting checked: $enabled")
            return enabled
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeViews()
        loadSettings()
        setupListeners()
    }

    private fun initializeViews() {
        randomStartSwitch = findViewById(R.id.randomStartSwitch)
        backButton = findViewById(R.id.backButton)  // This should work correctly now
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadSettings() {
        // Load saved settings
        val isRandomStartEnabled = preferences.getBoolean(PREF_RANDOM_START, false)
        Log.d("SettingsActivity", "Loading random start setting: $isRandomStartEnabled")
        randomStartSwitch.isChecked = isRandomStartEnabled
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            Log.d("SettingsActivity", "Back button pressed")
            finish()
        }

        randomStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SettingsActivity", "Random start setting changed to: $isChecked")
            // Save setting immediately when changed
            preferences.edit()
                .putBoolean(PREF_RANDOM_START, isChecked)
                .apply()

            // Log to verify the setting was saved
            Log.d("SettingsActivity", "Setting saved. Verification: ${preferences.getBoolean(PREF_RANDOM_START, false)}")
        }
    }
}