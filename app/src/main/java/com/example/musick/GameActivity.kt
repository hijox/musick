package com.example.musick

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track

class GameActivity : AppCompatActivity() {

    private lateinit var currentPlayerText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var scoreText: TextView
    private lateinit var buzzerButton: Button
    private lateinit var revealButton: Button
    private lateinit var nextButton: Button
    private lateinit var correctTitleButton: Button
    private lateinit var correctArtistButton: Button

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var currentTrack: Track? = null
    private var currentPlayerIndex = 0
    private lateinit var playerNames: List<String>
    private var scores = mutableMapOf<String, Int>()

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"

    private lateinit var playerScoresLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        initializeViews()
        setupGame()
        setupListeners()

        val playlistId = intent.getStringExtra("PLAYLIST_ID")
        connectToSpotify(playlistId)
    }

    private fun initializeViews() {
        currentPlayerText = findViewById(R.id.currentPlayerText)
        songInfoText = findViewById(R.id.songInfoText)
        scoreText = findViewById(R.id.scoreText)
        buzzerButton = findViewById(R.id.buzzerButton)
        revealButton = findViewById(R.id.revealButton)
        nextButton = findViewById(R.id.nextButton)
        correctTitleButton = findViewById(R.id.correctTitleButton)
        correctArtistButton = findViewById(R.id.correctArtistButton)
        playerScoresLayout = findViewById(R.id.playerScoresLayout)
    }

    private fun setupGame() {
        playerNames = intent.getStringArrayListExtra("PLAYER_NAMES") ?: listOf()
        scores = playerNames.associateWith { 0 }.toMutableMap()
        updateCurrentPlayer()
        updateScoreDisplay()
    }

    private fun setupListeners() {
        buzzerButton.setOnClickListener { activateBuzzer() }
        revealButton.setOnClickListener { revealSongInfo() }
        nextButton.setOnClickListener { nextTurn() }
        correctTitleButton.setOnClickListener { updateScore(1) }
        correctArtistButton.setOnClickListener { updateScore(1) }
    }

    private fun connectToSpotify(playlistId: String?) {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                playPlaylist(playlistId)
            }

            override fun onFailure(throwable: Throwable) {
                // Handle connection error
            }
        })
    }

    private fun playPlaylist(playlistId: String?) {
        spotifyAppRemote?.playerApi?.play("spotify:playlist:$playlistId")
        subscribeToPlayerState()
    }

    private fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            playerState.track?.let { track ->
                if (track != currentTrack) {
                    currentTrack = track
                    songInfoText.text = "Guess the song!"
                    buzzerButton.isEnabled = true
                }
            }
        }
    }

    private fun activateBuzzer() {
        spotifyAppRemote?.playerApi?.pause()
        buzzerButton.isEnabled = false
        revealButton.visibility = View.VISIBLE
    }

    private fun revealSongInfo() {
        currentTrack?.let { track ->
            songInfoText.text = "Song: ${track.name}\nArtist: ${track.artist.name}"
        }
        revealButton.visibility = View.GONE
        correctTitleButton.visibility = View.VISIBLE
        correctArtistButton.visibility = View.VISIBLE
        nextButton.visibility = View.VISIBLE
    }

    private fun updateScore(points: Int) {
        val currentPlayer = playerNames[currentPlayerIndex]
        scores[currentPlayer] = scores[currentPlayer]!! + points
        updateScoreDisplay()
    }

    private fun nextTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerNames.size
        updateCurrentPlayer()
        resetForNextSong()
        spotifyAppRemote?.playerApi?.skipNext()
    }

    private fun updateCurrentPlayer() {
        currentPlayerText.text = "Current Player: ${playerNames[currentPlayerIndex]}"
    }

    private fun resetForNextSong() {
        songInfoText.text = "Guess the song!"
        buzzerButton.isEnabled = true
        revealButton.visibility = View.GONE
        correctTitleButton.visibility = View.GONE
        correctArtistButton.visibility = View.GONE
        nextButton.visibility = View.GONE
    }

    private fun updateScoreDisplay() {
        playerScoresLayout.removeAllViews()
        scores.forEach { (player, score) ->
            val playerScoreLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }

            val playerScoreText = TextView(this).apply {
                text = "$player: $score"
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.white))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val buttonParams = LinearLayout.LayoutParams(
                dpToPx(40),
                dpToPx(40)
            ).apply {
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            }

            val createButton = { text: String, points: Int ->
                Button(this).apply {
                    this.text = text
                    textSize = 12f
                    setOnClickListener { updatePlayerScore(player, points) }
                    layoutParams = buttonParams
                    setPadding(0, 0, 0, 0)
                }
            }

            val minusTwoButton = createButton("-2", -2)
            val minusOneButton = createButton("-1", -1)
            val plusOneButton = createButton("+1", 1)
            val plusTwoButton = createButton("+2", 2)

            playerScoreLayout.addView(playerScoreText)
            playerScoreLayout.addView(minusTwoButton)
            playerScoreLayout.addView(minusOneButton)
            playerScoreLayout.addView(plusOneButton)
            playerScoreLayout.addView(plusTwoButton)

            playerScoresLayout.addView(playerScoreLayout)
        }
    }

    private fun updatePlayerScore(player: String, points: Int) {
        scores[player] = scores[player]!! + points
        updateScoreDisplay()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}