package com.example.musick

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track

class GameActivity : AppCompatActivity() {

    private lateinit var currentPlayerText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var buzzerButton: MaterialButton
    private lateinit var leftButton: MaterialButton
    private lateinit var rightButton: MaterialButton
    private lateinit var playerScoresRecyclerView: RecyclerView

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var currentTrack: Track? = null
    private var currentPlayerIndex = 0
    private lateinit var playerNames: List<String>
    private var scores = mutableMapOf<String, Int>()
    private var isSongPaused = false
    private var isSongRevealed = false

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"

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
        buzzerButton = findViewById(R.id.buzzerButton)
        leftButton = findViewById(R.id.leftButton)
        rightButton = findViewById(R.id.rightButton)
        playerScoresRecyclerView = findViewById(R.id.playerScoresRecyclerView)
    }

    private fun setupGame() {
        playerNames = intent.getStringArrayListExtra("PLAYER_NAMES") ?: listOf()
        scores = playerNames.associateWith { 0 }.toMutableMap()
        updateCurrentPlayer()
        setupScoresRecyclerView()
        updateButtonStates()
    }

    private fun setupScoresRecyclerView() {
        playerScoresRecyclerView.layoutManager = LinearLayoutManager(this)
        playerScoresRecyclerView.adapter = PlayerScoreAdapter(scores) { player, points ->
            updatePlayerScore(player, points)
        }
    }

    private fun setupListeners() {
        buzzerButton.setOnClickListener { toggleBuzzer() }
        leftButton.setOnClickListener { handleLeftButtonClick() }
        rightButton.setOnClickListener { handleRightButtonClick() }
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
        spotifyAppRemote?.playerApi?.let { playerApi ->
            playerApi.play("spotify:playlist:$playlistId")
            playerApi.setShuffle(true)
        }
        subscribeToPlayerState()
    }

    private fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            playerState.track?.let { track ->
                if (track != currentTrack) {
                    currentTrack = track
                    resetForNewSong()
                }
            }
        }
    }

    private fun toggleBuzzer() {
        if (!isSongRevealed) {
            if (isSongPaused) {
                spotifyAppRemote?.playerApi?.resume()
                isSongPaused = false
            } else {
                spotifyAppRemote?.playerApi?.pause()
                isSongPaused = true
            }
            updateButtonStates()
        }
    }

    private fun handleLeftButtonClick() {
        if (!isSongPaused) {
            skipSong()
        } else {
            revealSongInfo()
        }
    }

    private fun handleRightButtonClick() {
        nextTurn()
    }

    private fun revealSongInfo() {
        currentTrack?.let { track ->
            songInfoText.text = "Song: ${track.name}\nArtist: ${track.artist.name}"
        }
        isSongRevealed = true
        updateButtonStates()
    }

    private fun skipSong() {
        spotifyAppRemote?.playerApi?.skipNext()
        resetForNewSong()
    }

    private fun nextTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerNames.size
        updateCurrentPlayer()
        resetForNewSong()
        spotifyAppRemote?.playerApi?.skipNext()
    }

    private fun updateCurrentPlayer() {
        currentPlayerText.text = "Current Player: ${playerNames[currentPlayerIndex]}"
    }

    private fun resetForNewSong() {
        songInfoText.text = "Guess the song!"
        isSongPaused = false
        isSongRevealed = false
        updateButtonStates()
    }

    private fun updateButtonStates() {
        buzzerButton.isEnabled = !isSongRevealed

        when {
            isSongRevealed -> {
                leftButton.visibility = View.GONE
                rightButton.visibility = View.VISIBLE
                rightButton.text = "Next Turn"
                buzzerButton.text = "BUZZER"
            }
            isSongPaused -> {
                buzzerButton.text = "RESUME"
                leftButton.visibility = View.VISIBLE
                leftButton.text = "Reveal"
                rightButton.visibility = View.GONE
            }
            else -> {
                buzzerButton.text = "BUZZER"
                leftButton.visibility = View.VISIBLE
                leftButton.text = "Skip"
                rightButton.visibility = View.GONE
            }
        }
    }

    private fun updatePlayerScore(player: String, points: Int) {
        scores[player] = scores[player]!! + points
        playerScoresRecyclerView.adapter?.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}

class PlayerScoreAdapter(
    private val scores: Map<String, Int>,
    private val onScoreChange: (String, Int) -> Unit
) : RecyclerView.Adapter<PlayerScoreAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playerNameText: TextView = view.findViewById(R.id.playerNameText)
        val scoreText: TextView = view.findViewById(R.id.scoreText)
        val minusOneButton: MaterialButton = view.findViewById(R.id.minusOneButton)
        val plusOneButton: MaterialButton = view.findViewById(R.id.plusOneButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_score, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val player = scores.keys.elementAt(position)
        val score = scores[player] ?: 0

        holder.playerNameText.text = player
        holder.scoreText.text = score.toString()

        holder.minusOneButton.setOnClickListener { onScoreChange(player, -1) }
        holder.plusOneButton.setOnClickListener { onScoreChange(player, 1) }
    }

    override fun getItemCount() = scores.size
}