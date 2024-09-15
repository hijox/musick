package com.example.musick

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track
import com.spotify.protocol.types.ImageUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class GameActivity : AppCompatActivity() {

    private lateinit var currentPlayerText: TextView
    private lateinit var songInfoText: TextView
    private lateinit var buzzerButton: MaterialButton
    private lateinit var leftButton: MaterialButton
    private lateinit var rightButton: MaterialButton
    private lateinit var playerScoresRecyclerView: RecyclerView
    private lateinit var songProgressBar: ProgressBar
    private lateinit var albumArtworkImageView: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var mainContent: View
    private lateinit var gradientBackground: View

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var currentTrack: Track? = null
    private var currentPlayerIndex = 0
    private lateinit var playerNames: List<String>
    private var scores = mutableMapOf<String, Int>()
    private var isSongPaused = false
    private var isSongRevealed = false

    private lateinit var pulseAnimatorSet: AnimatorSet
    private lateinit var pulseAnimator: ValueAnimator
    private lateinit var gradientAnimation: AnimationDrawable

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"

    private var isInitializing = true
    private var pendingPlaylistId: String? = null
    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        pendingPlaylistId = intent.getStringExtra("PLAYLIST_ID")

        initializeViews()
        connectToSpotify(loadAnimation=true)
        setupGame()
        setupListeners()
        createPulsatingEffect()
        setPlaceholderAlbumArt()
    }

    override fun onResume() {
        super.onResume()
        if (spotifyAppRemote?.isConnected != true && !isConnecting) {
            connectToSpotify(loadAnimation=false)
        } else if (spotifyAppRemote?.isConnected == true) {
            updateCurrentSongInfo()
        }
    }

    private fun updateCurrentSongInfo() {
        spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
            playerState.track?.let { track ->
                currentTrack = track
                if (isSongRevealed) {
                    revealSongInfo()
                } else {
                    songInfoText.text = "Guess the song!"
                }
            }
        }
    }

    private fun initializeViews() {
        currentPlayerText = findViewById(R.id.currentPlayerText)
        songInfoText = findViewById(R.id.songInfoText)
        buzzerButton = findViewById(R.id.buzzerButton)
        leftButton = findViewById(R.id.leftButton)
        rightButton = findViewById(R.id.rightButton)
        playerScoresRecyclerView = findViewById(R.id.playerScoresRecyclerView)
        songProgressBar = findViewById(R.id.songProgressBar)
        albumArtworkImageView = findViewById(R.id.albumArtworkImageView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        mainContent = findViewById(R.id.mainContent)
        gradientBackground = findViewById(R.id.gradientBackground)
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
        buzzerButton.setOnClickListener {
            vibrator(100)
            toggleBuzzer()
        }
        leftButton.setOnClickListener {
            handleLeftButtonClick()
        }
        rightButton.setOnClickListener {
            handleRightButtonClick()
        }
    }

    private fun connectToSpotify(loadAnimation: Boolean) {
        if (isConnecting) return

        isConnecting = true
        if (loadAnimation) {
            showLoadingState()
        }
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                isConnecting = false
                if (loadAnimation) {
                    showMainContent()
                    startPulsatingEffect()
                }
                runOnUiThread {
                    Toast.makeText(this@GameActivity, "Connected to Spotify", Toast.LENGTH_SHORT).show()
                }
                if (isInitializing && currentTrack == null) {
                    isInitializing = false
                    pendingPlaylistId?.let { playlistId ->
                        playPlaylist(playlistId)
                    }
                } else {
                    subscribeToPlayerState()
                    updateCurrentSongInfo()
                    if (!isSongPaused) {
                        startPulsatingEffect()
                    }
                }
            }

            override fun onFailure(throwable: Throwable) {
                isConnecting = false
                runOnUiThread {
                    Toast.makeText(this@GameActivity, "Failed to connect to Spotify", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun playPlaylist(playlistId: String) {
        spotifyAppRemote?.playerApi?.let { playerApi ->
            playerApi.setShuffle(true)
            playerApi.play("spotify:playlist:$playlistId").setResultCallback {
                subscribeToPlayerState()
            }
        }
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
                startPulsatingEffect()
                startProgressBarAnimation()
            } else {
                spotifyAppRemote?.playerApi?.pause()
                isSongPaused = true
                stopPulsatingEffect()
                stopProgressBarAnimation()
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
            loadAlbumArtwork(track.imageUri)
        }
        isSongRevealed = true
        updateButtonStates()
    }

    private fun setPlaceholderAlbumArt() {
        Glide.with(this)
            .load(R.drawable.placeholder_album_art)
            .into(albumArtworkImageView)
    }

    private fun loadAlbumArtwork(imageUri: ImageUri) {
        spotifyAppRemote?.imagesApi?.getImage(imageUri)?.setResultCallback { bitmap ->
            Glide.with(this)
                .load(bitmap)
                .placeholder(R.drawable.placeholder_album_art)
                .error(R.drawable.placeholder_album_art)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(albumArtworkImageView)
        }
    }

    private fun skipSong() {
        spotifyAppRemote?.playerApi?.skipNext()
        resetForNewSong()
    }

    private fun nextTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerNames.size
        updateCurrentPlayer()
        resetForNewSong()
        startPulsatingEffect()
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
                buzzerButton.setBackgroundColor(ContextCompat.getColor(this, R.color.spotify_green))
            }
            isSongPaused -> {
                buzzerButton.text = "RESUME"
                leftButton.visibility = View.VISIBLE
                leftButton.text = "Reveal"
                rightButton.visibility = View.GONE
                buzzerButton.setBackgroundColor(ContextCompat.getColor(this, R.color.spotify_orange))
            }
            else -> {
                buzzerButton.text = "BUZZER"
                leftButton.visibility = View.VISIBLE
                leftButton.text = "Skip"
                rightButton.visibility = View.GONE
                buzzerButton.setBackgroundColor(ContextCompat.getColor(this, R.color.spotify_green))
            }
        }
    }

    private fun updatePlayerScore(player: String, points: Int) {
        scores[player] = scores[player]!! + points
        playerScoresRecyclerView.adapter?.notifyDataSetChanged()
    }

    private fun showLoadingState() {
        mainContent.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE
    }

    private fun showMainContent() {
        mainContent.visibility = View.VISIBLE
        loadingIndicator.visibility = View.GONE
    }

    private fun createPulsatingEffect() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000 // 1 second for the full animation cycle
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Float
                val scale = 1f + (0.1f * animatedValue) // Scale from 1.0 to 1.1
                buzzerButton.scaleX = scale
                buzzerButton.scaleY = scale
            }
        }
    }

    private fun startPulsatingEffect() {
        if (!pulseAnimator.isStarted) {
            pulseAnimator.start()
        }
    }

    private fun stopPulsatingEffect() {
        pulseAnimator.cancel()
        buzzerButton.scaleX = 1f
        buzzerButton.scaleY = 1f
    }

    private fun startProgressBarAnimation() {
        songProgressBar.visibility = View.VISIBLE
        val animator = ObjectAnimator.ofInt(songProgressBar, "progress", 0, 100)
        animator.duration = 30000 // Assuming 30 seconds for each song
        animator.start()
    }

    private fun stopProgressBarAnimation() {
        songProgressBar.clearAnimation()
    }

    override fun onStop() {
        super.onStop()
        SpotifyAppRemote.disconnect(spotifyAppRemote)
        isInitializing = true
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

        // Find the highest score in the list
        val maxScore = scores.values.maxOrNull() ?: 0

        // Set score and player name color based on whether it's the highest score
        if (score == maxScore && score > 0) {
            holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
            holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
        } else {
            holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.white))
            holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.white))
        }

        // Update score with button clicks
        holder.minusOneButton.setOnClickListener { onScoreChange(player, -1) }
        holder.plusOneButton.setOnClickListener { onScoreChange(player, 1) }
    }

    override fun getItemCount() = scores.size
}

fun Context.vibrator(durationMillis: Long = 50) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // For Android 12 (S) and above
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrationEffect = VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(vibrationEffect)
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
            // For Android 8.0 (Oreo) to Android 11 (R)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val vibrationEffect = VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(vibrationEffect)
        }

        else -> {
            // For Android versions below Oreo (API level 26)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(durationMillis)
        }
    }
}