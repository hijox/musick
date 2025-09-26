package com.example.musick

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var randomStartSwitch: SwitchMaterial
    private lateinit var backButton: ImageView
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
        backButton = findViewById(R.id.backButton)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadSettings() {
        // Load saved settings
        randomStartSwitch.isChecked = preferences.getBoolean(PREF_RANDOM_START, false)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }

        randomStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d("SettingsActivity", "Random start setting changed to: $isChecked")
            // Save setting immediately when changed
            preferences.edit()
                .putBoolean(PREF_RANDOM_START, isChecked)
                .apply()
        }
    }
}
