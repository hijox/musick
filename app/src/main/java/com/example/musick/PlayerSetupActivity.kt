package com.example.musick

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class PlayerSetupActivity : AppCompatActivity() {

    private lateinit var playerCountInput: EditText
    private lateinit var generateNamesButton: Button
    private lateinit var playerNamesLayout: LinearLayout
    private lateinit var startGameButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_setup)

        playerCountInput = findViewById(R.id.playerCountInput)
        generateNamesButton = findViewById(R.id.generateNamesButton)
        playerNamesLayout = findViewById(R.id.playerNamesLayout)
        startGameButton = findViewById(R.id.startGameButton)

        generateNamesButton.setOnClickListener {
            generatePlayerNameInputs()
        }

        startGameButton.setOnClickListener {
            startGame()
        }
    }

    private fun generatePlayerNameInputs() {
        playerNamesLayout.removeAllViews()
        val playerCount = playerCountInput.text.toString().toIntOrNull() ?: 0

        for (i in 1..playerCount) {
            val playerNameInput = EditText(this)
            playerNameInput.hint = "Enter player $i name"
            playerNamesLayout.addView(playerNameInput)
        }

        startGameButton.isEnabled = true
    }

    private fun startGame() {
        val playerNames = mutableListOf<String>()

        for (i in 0 until playerNamesLayout.childCount) {
            val nameInput = playerNamesLayout.getChildAt(i) as EditText
            playerNames.add(nameInput.text.toString())
        }

        val playlistId = intent.getStringExtra("PLAYLIST_ID")
        val intent = Intent(this, GameActivity::class.java)
        intent.putStringArrayListExtra("PLAYER_NAMES", ArrayList(playerNames))
        intent.putExtra("PLAYLIST_ID", playlistId)
        startActivity(intent)
    }
}