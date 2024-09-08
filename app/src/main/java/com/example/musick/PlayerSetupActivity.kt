package com.example.musick

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class PlayerSetupActivity : AppCompatActivity() {

    private lateinit var playerCountInput: TextInputEditText
    private lateinit var generateNamesButton: MaterialButton
    private lateinit var playerNamesLayout: LinearLayout
    private lateinit var startGameButton: MaterialButton

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
            val inputLayout = TextInputLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                hint = "Player $i name"
                setBoxBackgroundColor(resources.getColor(R.color.dark_gray, theme))
                setBoxStrokeColor(resources.getColor(R.color.spotify_green, theme))
                setHintTextColor(ColorStateList.valueOf(resources.getColor(R.color.gray, theme)))
                // Disable the floating label behavior
                isHintAnimationEnabled = false
                isHintEnabled = true
            }

            val playerNameInput = TextInputEditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setTextColor(resources.getColor(R.color.white, theme))
                setBackgroundResource(android.R.color.transparent)

                // Add TextWatcher to handle hint visibility
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        inputLayout.isHintEnabled = s.isNullOrEmpty()
                    }

                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            inputLayout.addView(playerNameInput)
            playerNamesLayout.addView(inputLayout)
        }

        startGameButton.isEnabled = playerCount > 0
    }

    private fun startGame() {
        val playerNames = mutableListOf<String>()

        for (i in 0 until playerNamesLayout.childCount) {
            val inputLayout = playerNamesLayout.getChildAt(i) as TextInputLayout
            val nameInput = inputLayout.editText
            playerNames.add(nameInput?.text.toString())
        }

        val playlistId = intent.getStringExtra("PLAYLIST_ID")
        val intent = Intent(this, GameActivity::class.java)
        intent.putStringArrayListExtra("PLAYER_NAMES", ArrayList(playerNames))
        intent.putExtra("PLAYLIST_ID", playlistId)
        startActivity(intent)
    }
}