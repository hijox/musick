package com.example.musick

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlayerSetupActivity : AppCompatActivity() {

    private lateinit var playerCountInput: TextInputEditText
    private lateinit var playerNamesLayout: LinearLayout
    private lateinit var startGameButton: MaterialButton
    private lateinit var sharedPreferences: SharedPreferences
    private var playerNames: MutableList<String> = mutableListOf()
    private var isLoadingOldConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_setup)

        playerCountInput = findViewById(R.id.playerCountInput)
        playerNamesLayout = findViewById(R.id.playerNamesLayout)
        startGameButton = findViewById(R.id.startGameButton)

        sharedPreferences = getSharedPreferences("PlayerSetup", Context.MODE_PRIVATE)

        setupPlayerCountInput()
        loadLastConfiguration()
        setupStartGameButton()
    }

    private fun setupPlayerCountInput() {
        playerCountInput.doAfterTextChanged { editable ->
            if (!isLoadingOldConfig) {
                val count = editable.toString().toIntOrNull() ?: 0
                generatePlayerNameInputs(count)
            }
        }
    }

    private fun generatePlayerNameInputs(count: Int) {
        playerNamesLayout.removeAllViews()
        val oldSize = playerNames.size
        playerNames = playerNames.take(count).toMutableList()

        for (i in playerNames.size until count) {
            playerNames.add("")
        }

        for (i in 0 until count) {
            val inputLayout = createPlayerNameInputLayout(i)
            val playerNameInput = createPlayerNameInput(i, inputLayout, count)
            inputLayout.addView(playerNameInput)
            playerNamesLayout.addView(inputLayout)

            // Hide hint for filled fields from old config
            if (i < oldSize && playerNames[i].isNotBlank()) {
                inputLayout.isHintEnabled = false
            }
        }

        updateStartGameButtonState()
    }

    private fun createPlayerNameInputLayout(index: Int): TextInputLayout {
        return TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            hint = "Player ${index + 1} name"
            setBoxBackgroundColor(ContextCompat.getColor(context, R.color.dark_gray))
            setBoxStrokeColor(ContextCompat.getColor(context, R.color.spotify_green))
            setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.gray)))
            isHintAnimationEnabled = false
            isHintEnabled = true
        }
    }

    private fun createPlayerNameInput(index: Int, inputLayout: TextInputLayout, totalCount: Int): TextInputEditText {
        return TextInputEditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setBackgroundResource(android.R.color.transparent)
            setText(playerNames.getOrNull(index))
            isSingleLine = true

            // Set imeOptions based on whether this is the last input field
            imeOptions = if (index == totalCount - 1) {
                EditorInfo.IME_ACTION_DONE
            } else {
                EditorInfo.IME_ACTION_NEXT
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    inputLayout.isHintEnabled = s.isNullOrEmpty()
                    updatePlayerName(index, s.toString())
                    updateStartGameButtonState()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }

    private fun updatePlayerName(index: Int, name: String) {
        if (index >= playerNames.size) {
            playerNames.add(name)
        } else {
            playerNames[index] = name
        }
    }

    private fun updateStartGameButtonState() {
        startGameButton.isEnabled = playerNames.isNotEmpty() && playerNames.all { it.isNotBlank() }
    }

    private fun setupStartGameButton() {
        startGameButton.setOnClickListener {
            saveConfiguration()
            startGame()
        }
    }

    private fun startGame() {
        val playlistId = intent.getStringExtra("PLAYLIST_ID")
        val intent = Intent(this, GameActivity::class.java)
        intent.putStringArrayListExtra("PLAYER_NAMES", ArrayList(playerNames))
        intent.putExtra("PLAYLIST_ID", playlistId)
        startActivity(intent)
    }

    private fun saveConfiguration() {
        val editor = sharedPreferences.edit()
        editor.putInt("playerCount", playerNames.size)
        editor.putString("playerNames", Gson().toJson(playerNames))
        editor.apply()
    }

    private fun loadLastConfiguration() {
        val savedPlayerCount = sharedPreferences.getInt("playerCount", 0)
        val savedPlayerNamesJson = sharedPreferences.getString("playerNames", null)

        if (savedPlayerCount > 0 && savedPlayerNamesJson != null) {
            isLoadingOldConfig = true
            val type = object : TypeToken<List<String>>() {}.type
            playerNames = Gson().fromJson(savedPlayerNamesJson, type)
            playerCountInput.setText(savedPlayerCount.toString())
            generatePlayerNameInputs(savedPlayerCount)
            isLoadingOldConfig = false
        }
    }
}