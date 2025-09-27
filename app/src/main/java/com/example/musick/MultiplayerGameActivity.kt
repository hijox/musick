package com.example.musick

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musick.SpotifyManager.spotifyAppRemote
import com.example.musick.multiplayer.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.Track
import kotlinx.coroutines.*
import java.util.UUID

class MultiplayerGameActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiplayerGame"
        private const val BUZZER_TIMEOUT_MS = 30000L // 30 seconds to buzz
    }

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var songNameText: TextView
    private lateinit var artistNameText: TextView
    private lateinit var albumArtworkImageView: ImageView
    private lateinit var buzzerButton: ImageView
    private lateinit var playIcon: ImageView
    private lateinit var pauseIcon: ImageView
    private lateinit var controlButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var songProgressBar: ProgressBar
    private lateinit var loadingOverlay: View
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var buzzerStatusCard: MaterialCardView
    private lateinit var buzzerStatusText: TextView
    private lateinit var winnerCard: MaterialCardView
    private lateinit var winnerText: TextView

    // Game state
    private var isHost = false
    private var currentPlayer: MultiplayerPlayer? = null
    private var allPlayers = mutableListOf<MultiplayerPlayer>()
    private var playlistId: String? = null
    private var currentTrack: Track? = null
    private var isSongPaused = false
    private var isSongRevealed = false
    private var currentRoundId: String? = null
    private var buzzerWinner: MultiplayerPlayer? = null
    private var buzzerPressed = false
    private var roundActive = false

    // Multiplayer components
    private lateinit var wifiDirectManager: WiFiDirectManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Adapters
    private lateinit var playersAdapter: MultiplayerScoreAdapter

    // Animations
    private lateinit var spinningAnimator: ValueAnimator
    private var lastRotation: Float = 0f
    private var pausePulseAnimator: ValueAnimator? = null
    private var playPulseAnimator: ValueAnimator? = null
    private val baseIconScale = 1.0f
    private val pulseScale = 1.20f
    private val pulseDuration = 1500L

    // Progress tracking
    private var isProgressBarUpdating = false
    private var albumArtCache = mutableMapOf<ImageUri, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiplayer_game)

        extractIntentData()
        initializeViews()
        setupSpinningAnimation()
        setupPulseAnimations()
        setupProgressBar()
        setupMultiplayer()
        setupListeners()
        setupRecyclerView()

        showLoading("Connecting to Spotify")
        ensureSpotifyConnection()
    }

    private fun extractIntentData() {
        isHost = intent.getBooleanExtra("IS_HOST", false)
        playlistId = intent.getStringExtra("PLAYLIST_ID")
        currentPlayer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("CURRENT_PLAYER", MultiplayerPlayer::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("CURRENT_PLAYER")
        }
        
        val players = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("CONNECTED_PLAYERS", MultiplayerPlayer::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("CONNECTED_PLAYERS")
        }
        
        players?.let { allPlayers.addAll(it) }

        Log.d(TAG, "Initialized - Host: $isHost, Players: ${allPlayers.size}")
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        songNameText = findViewById(R.id.songNameText)
        artistNameText = findViewById(R.id.artistNameText)
        albumArtworkImageView = findViewById(R.id.albumArtworkImageView)
        buzzerButton = findViewById(R.id.buzzerButton)
        playIcon = findViewById(R.id.playIcon)
        pauseIcon = findViewById(R.id.pauseIcon)
        controlButton = findViewById(R.id.controlButton)
        skipButton = findViewById(R.id.skipButton)
        playersRecyclerView = findViewById(R.id.playersRecyclerView)
        songProgressBar = findViewById(R.id.songProgressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        loadingText = findViewById(R.id.loadingText)
        buzzerStatusCard = findViewById(R.id.buzzerStatusCard)
        buzzerStatusText = findViewById(R.id.buzzerStatusText)
        winnerCard = findViewById(R.id.winnerCard)
        winnerText = findViewById(R.id.winnerText)

        // Initial visibility
        songNameText.visibility = View.GONE
        artistNameText.visibility = View.GONE
        albumArtworkImageView.visibility = View.GONE
        buzzerStatusCard.visibility = View.GONE
        winnerCard.visibility = View.GONE

        // Set initial status
        statusText.text = if (isHost) "You are hosting the game" else "Connected to game"
    }

    private fun setupMultiplayer() {
        // Initialize WiFi Direct manager (should be passed from setup activity)
        // For now, we'll create a new instance - in production this should be shared
        wifiDirectManager = WiFiDirectManager(this).apply {
            onMessageReceived = { message ->
                handleMultiplayerMessage(message)
            }
            onDeviceDisconnected = { address ->
                runOnUiThread {
                    handlePlayerDisconnected(address)
                }
            }
        }
        
        if (!wifiDirectManager.initialize()) {
            Toast.makeText(this, "Failed to initialize multiplayer", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupListeners() {
        buzzerButton.setOnClickListener {
            handleBuzzerClick()
        }

        albumArtworkImageView.setOnClickListener {
            if (isHost && isSongRevealed) {
                nextRound()
            }
        }

        controlButton.setOnClickListener {
            if (isHost) {
                when {
                    !roundActive && !isSongRevealed -> startNewRound()
                    roundActive && isSongPaused -> revealSong()
                    isSongRevealed -> nextRound()
                }
            }
        }

        skipButton.setOnClickListener {
            if (isHost) {
                skipSong()
            }
        }
    }

    private fun setupRecyclerView() {
        playersAdapter = MultiplayerScoreAdapter(
            players = allPlayers,
            onScoreChange = if (isHost) { playerId, delta ->
                updatePlayerScore(playerId, delta)
            } else null,
            isHost = isHost
        )
        
        playersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MultiplayerGameActivity)
            adapter = playersAdapter
        }
    }

    private fun ensureSpotifyConnection() {
        if (!isHost) {
            // Non-host devices don't need Spotify connection
            hideLoading()
            updateUI()
            return
        }

        coroutineScope.launch {
            try {
                if (!SpotifyManager.isConnected()) {
                    val connected = SpotifyManager.connectToSpotifyAppRemote(this@MultiplayerGameActivity)
                    if (!connected) {
                        showErrorToast("Failed to connect to Spotify")
                        finish()
                        return@launch
                    }
                }
                
                // Start the first round
                playlistId?.let { playPlaylist(it) }
                hideLoading()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                showErrorToast("Connection failed: ${e.message}")
                finish()
            }
        }
    }

    private fun playPlaylist(playlistId: String) {
        if (!isHost) return

        try {
            spotifyAppRemote?.playerApi?.let { playerApi ->
                playerApi.setShuffle(true)
                playerApi.play("spotify:playlist:$playlistId")
                
                // Wait a moment then start first round
                mainHandler.postDelayed({
                    startNewRound()
                }, 2000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing playlist", e)
            showErrorToast("Failed to play playlist")
        }
    }

    private fun startNewRound() {
        if (!isHost) return

        currentRoundId = UUID.randomUUID().toString()
        roundActive = true
        isSongRevealed = false
        buzzerPressed = false
        buzzerWinner = null

        // Apply random start if enabled
        if (SettingsActivity.isRandomStartEnabled(this)) {
            applyRandomStart()
        } else {
            startSong()
        }

        // Broadcast round start to all players
        broadcastGameStateUpdate()
        
        // Show buzzer status
        buzzerStatusCard.visibility = View.VISIBLE
        buzzerStatusText.text = "Round active - buzz to answer!"
        winnerCard.visibility = View.GONE

        updateUI()

        // Set timeout for round
        mainHandler.postDelayed({
            if (roundActive && !buzzerPressed) {
                timeoutRound()
            }
        }, BUZZER_TIMEOUT_MS)
    }

    private fun handleBuzzerClick() {
        if (!roundActive || buzzerPressed) return

        vibrateSafe(100)

        if (isHost) {
            // Host buzzed - process immediately
            processBuzz(currentPlayer?.id ?: "")
        } else {
            // Client buzzed - send to host
            currentPlayer?.let { player ->
                val buzzMessage = MultiplayerMessage.BuzzerPressed(
                    timestamp = System.currentTimeMillis(),
                    senderId = player.id
                )
                wifiDirectManager.sendMessage(buzzMessage)
                
                // Disable buzzer locally
                buzzerPressed = true
                updateUI()
            }
        }
    }

    private fun processBuzz(playerId: String) {
        if (!isHost || !roundActive || buzzerPressed) return

        buzzerPressed = true
        roundActive = false
        
        // Find the player who buzzed
        buzzerWinner = allPlayers.find { it.id == playerId }
        
        // Pause the song
        pauseSong()
        
        // Show winner
        buzzerWinner?.let { winner ->
            winnerCard.visibility = View.VISIBLE
            winnerText.text = "${winner.name} buzzed first!"
            buzzerStatusCard.visibility = View.GONE
        }
        
        // Broadcast the result
        val resultMessage = MultiplayerMessage.BuzzerPressed(
            timestamp = System.currentTimeMillis(),
            senderId = playerId
        )
        wifiDirectManager.sendMessage(resultMessage)
        
        updateUI()
    }

    private fun revealSong() {
        if (!isHost) return

        getTrackInfosSafe { track, artistName, albumCoverImageUri, _ ->
            currentTrack = track
            runOnUiThread {
                try {
                    songNameText.apply {
                        text = track?.name ?: "Unknown Song"
                        visibility = View.VISIBLE
                    }
                    artistNameText.apply {
                        text = artistName
                        visibility = View.VISIBLE
                    }
                    isSongRevealed = true
                    transformBuzzerToAlbumCover(albumCoverImageUri)
                    updateUI()
                    
                    // Broadcast song reveal
                    val revealMessage = MultiplayerMessage.SongRevealed(
                        timestamp = System.currentTimeMillis(),
                        senderId = currentPlayer?.id ?: "",
                        songName = track?.name ?: "Unknown",
                        artistName = artistName
                    )
                    wifiDirectManager.sendMessage(revealMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating UI in reveal", e)
                }
            }
        }
    }

    private fun nextRound() {
        if (!isHost) return

        resetForNewRound()
        
        try {
            spotifyAppRemote?.playerApi?.skipNext()
            
            mainHandler.postDelayed({
                startNewRound()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error in next round", e)
            showErrorToast("Failed to skip to next song")
        }
    }

    private fun skipSong() {
        if (!isHost) return
        
        nextRound()
    }

    private fun timeoutRound() {
        if (!isHost) return

        roundActive = false
        buzzerStatusCard.visibility = View.GONE
        winnerCard.visibility = View.VISIBLE
        winnerText.text = "Time's up! No one buzzed."
        
        // Reveal the song anyway
        revealSong()
    }

    private fun resetForNewRound() {
        roundActive = false
        isSongRevealed = false
        buzzerPressed = false
        buzzerWinner = null
        currentRoundId = null
        
        // Reset UI
        transformAlbumCoverToBuzzer()
        songNameText.visibility = View.GONE
        artistNameText.visibility = View.GONE
        buzzerStatusCard.visibility = View.GONE
        winnerCard.visibility = View.GONE
        
        // Reset progress bar
        runOnUiThread {
            songProgressBar.progress = 0
        }
        
        updateUI()
    }

    private fun updatePlayerScore(playerId: String, delta: Int) {
        if (!isHost) return

        val player = allPlayers.find { it.id == playerId }
        player?.let {
            it.score += delta
            playersAdapter.notifyDataSetChanged()
            
            // Broadcast score update
            val scoreMessage = MultiplayerMessage.ScoreUpdate(
                timestamp = System.currentTimeMillis(),
                senderId = currentPlayer?.id ?: "",
                playerId = playerId,
                newScore = it.score
            )
            wifiDirectManager.sendMessage(scoreMessage)
        }
    }

    private fun handleMultiplayerMessage(message: MultiplayerMessage) {
        runOnUiThread {
            when (message) {
                is MultiplayerMessage.BuzzerPressed -> {
                    if (isHost && roundActive && !buzzerPressed) {
                        processBuzz(message.senderId)
                    } else if (!isHost) {
                        // Client received buzz result
                        val winner = allPlayers.find { it.id == message.senderId }
                        winner?.let {
                            winnerCard.visibility = View.VISIBLE
                            winnerText.text = "${it.name} buzzed first!"
                            buzzerStatusCard.visibility = View.GONE
                            buzzerPressed = true
                            roundActive = false
                            updateUI()
                        }
                    }
                }
                
                is MultiplayerMessage.SongRevealed -> {
                    if (!isHost) {
                        songNameText.apply {
                            text = message.songName
                            visibility = View.VISIBLE
                        }
                        artistNameText.apply {
                            text = message.artistName
                            visibility = View.VISIBLE
                        }
                        isSongRevealed = true
                        updateUI()
                    }
                }
                
                is MultiplayerMessage.ScoreUpdate -> {
                    if (!isHost) {
                        val player = allPlayers.find { it.id == message.playerId }
                        player?.let {
                            it.score = message.newScore
                            playersAdapter.notifyDataSetChanged()
                        }
                    }
                }
                
                is MultiplayerMessage.GameStateUpdate -> {
                    // Handle game state synchronization
                    allPlayers.clear()
                    allPlayers.addAll(message.gameState.players)
                    playersAdapter.notifyDataSetChanged()
                }
                
                is MultiplayerMessage.NextSong -> {
                    if (!isHost) {
                        resetForNewRound()
                    }
                }
                
                else -> {
                    Log.d(TAG, "Unhandled message: ${message.javaClass.simpleName}")
                }
            }
        }
    }

    private fun handlePlayerDisconnected(address: String) {
        allPlayers.removeAll { it.deviceAddress == address }
        playersAdapter.notifyDataSetChanged()
        
        Toast.makeText(this, "Player disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun broadcastGameStateUpdate() {
        if (!isHost) return
        
        val gameState = GameState(
            currentSong = currentTrack?.name,
            currentArtist = currentTrack?.artist?.name,
            isRevealed = isSongRevealed,
            isPaused = isSongPaused,
            players = allPlayers,
            gameStarted = true
        )
        
        val message = MultiplayerMessage.GameStateUpdate(
            timestamp = System.currentTimeMillis(),
            senderId = currentPlayer?.id ?: "",
            gameState = gameState
        )
        
        wifiDirectManager.sendMessage(message)
    }

    private fun updateUI() {
        // Update control button
        when {
            isHost && !roundActive && !isSongRevealed -> {
                controlButton.visibility = View.VISIBLE
                controlButton.text = "Start Round"
                controlButton.isEnabled = true
            }
            isHost && roundActive && isSongPaused -> {
                controlButton.visibility = View.VISIBLE
                controlButton.text = "Reveal Song"
                controlButton.isEnabled = true
            }
            isHost && isSongRevealed -> {
                controlButton.visibility = View.VISIBLE
                controlButton.text = "Next Song"
                controlButton.isEnabled = true
            }
            else -> {
                controlButton.visibility = View.GONE
            }
        }

        // Update skip button - only visible for host
        skipButton.visibility = if (isHost) View.VISIBLE else View.GONE

        // Update buzzer button state
        buzzerButton.isEnabled = roundActive && !buzzerPressed
        buzzerButton.alpha = if (buzzerButton.isEnabled) 1.0f else 0.5f
    }

    // Spotify-related helper methods (adapted from GameActivity)
    private fun startSong() {
        if (!isHost) return
        
        try {
            if (isSongPaused) {
                spotifyAppRemote?.playerApi?.resume()
            }
            isSongPaused = false
            pauseIcon.visibility = View.VISIBLE
            playIcon.visibility = View.GONE
            startSpinningAnimation()
            startPausePulse()
            startProgressBarUpdateSafe()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting song", e)
        }
    }

    private fun pauseSong() {
        if (!isHost) return
        
        try {
            spotifyAppRemote?.playerApi?.pause()
            isSongPaused = true
            pauseIcon.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            pauseSpinningAnimation()
            stopAllPulseAnimations()
            stopProgressBarUpdateSafe()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing song", e)
        }
    }

    private fun applyRandomStart() {
        if (!isHost) return
        
        // Simple random start implementation
        mainHandler.postDelayed({
            getTrackInfosSafe { track, _, _, _ ->
                if (track != null && track.duration > 0) {
                    val songDuration = track.duration
                    val firstThirdDuration = songDuration / 3
                    val minStartPosition = 10000L // 10 seconds
                    val maxStartPosition = maxOf(minStartPosition, firstThirdDuration)
                    val randomPosition = (minStartPosition..maxStartPosition).random()
                    
                    spotifyAppRemote?.playerApi?.seekTo(randomPosition)?.setResultCallback {
                        startSong()
                    }
                } else {
                    startSong()
                }
            }
        }, 1000)
    }

    private fun getTrackInfosSafe(callback: (Track?, String, ImageUri?, Long) -> Unit) {
        if (!isHost) return
        
        try {
            spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                try {
                    val track = playerState?.track
                    currentTrack = track
                    val artistName = track?.artist?.name ?: ""
                    val albumCoverImageUri = track?.imageUri
                    val progress = playerState?.playbackPosition ?: 0L

                    callback(track, artistName, albumCoverImageUri, progress)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing track info", e)
                }
            }?.setErrorCallback { throwable ->
                Log.e(TAG, "Error getting track info", throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up track info callback", e)
        }
    }

    // Animation methods (adapted from GameActivity)
    private fun setupSpinningAnimation() {
        try {
            spinningAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    try {
                        if (!isFinishing && !isDestroyed) {
                            val rotation = animator.animatedValue as Float
                            buzzerButton.rotation = rotation
                            lastRotation = rotation
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in animation update", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up spinning animation", e)
        }
    }

    private fun setupPulseAnimations() {
        try {
            pausePulseAnimator = ValueAnimator.ofFloat(baseIconScale, pulseScale, baseIconScale).apply {
                duration = pulseDuration
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    try {
                        if (!isFinishing && !isDestroyed) {
                            val scale = animator.animatedValue as Float
                            pauseIcon.scaleX = scale
                            pauseIcon.scaleY = scale
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in pause pulse animation", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up pulse animations", e)
        }
    }

    private fun startSpinningAnimation() {
        if (!::spinningAnimator.isInitialized) {
            setupSpinningAnimation()
        }
        if (!spinningAnimator.isRunning) {
            spinningAnimator.start()
        }
    }

    private fun pauseSpinningAnimation() {
        if (::spinningAnimator.isInitialized && spinningAnimator.isRunning) {
            lastRotation = buzzerButton.rotation
            spinningAnimator.cancel()
            buzzerButton.rotation = lastRotation
        }
    }

    private fun startPausePulse() {
        if (!isSongPaused && pausePulseAnimator?.isRunning != true) {
            pausePulseAnimator?.start()
        }
    }

    private fun stopAllPulseAnimations() {
        pausePulseAnimator?.cancel()
        playPulseAnimator?.cancel()
        pauseIcon.scaleX = baseIconScale
        pauseIcon.scaleY = baseIconScale
        playIcon.scaleX = baseIconScale
        playIcon.scaleY = baseIconScale
    }

    private fun transformBuzzerToAlbumCover(albumCoverImageUri: ImageUri?) {
        try {
            val fadeOut = ObjectAnimator.ofFloat(buzzerButton, "alpha", 1f, 0f)
            val fadeIn = ObjectAnimator.ofFloat(albumArtworkImageView, "alpha", 0f, 1f)

            AnimatorSet().apply {
                playTogether(fadeOut, fadeIn)
                duration = 500
                doOnStart {
                    loadAlbumArtworkSafe(albumCoverImageUri)
                    albumArtworkImageView.visibility = View.VISIBLE
                }
                doOnEnd {
                    buzzerButton.visibility = View.GONE
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in buzzer to album animation", e)
            buzzerButton.visibility = View.GONE
            albumArtworkImageView.visibility = View.VISIBLE
            loadAlbumArtworkSafe(albumCoverImageUri)
        }
    }

    private fun transformAlbumCoverToBuzzer() {
        try {
            val fadeOut = ObjectAnimator.ofFloat(albumArtworkImageView, "alpha", 1f, 0f)
            val fadeIn = ObjectAnimator.ofFloat(buzzerButton, "alpha", 0f, 1f)

            AnimatorSet().apply {
                playTogether(fadeOut, fadeIn)
                duration = 500
                doOnStart {
                    buzzerButton.visibility = View.VISIBLE
                }
                doOnEnd {
                    albumArtworkImageView.visibility = View.GONE
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in album to buzzer animation", e)
            albumArtworkImageView.visibility = View.GONE
            buzzerButton.visibility = View.VISIBLE
        }
    }

    private fun loadAlbumArtworkSafe(imageUri: ImageUri?) {
        if (!isHost) return
        
        imageUri?.let { uri ->
            val cachedBitmap = albumArtCache[uri]
            if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                runOnUiThread {
                    try {
                        if (!isFinishing && !isDestroyed) {
                            albumArtworkImageView.setImageBitmap(cachedBitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting cached bitmap", e)
                    }
                }
            } else {
                try {
                    spotifyAppRemote?.imagesApi?.getImage(imageUri)?.setResultCallback { bitmap ->
                        if (bitmap != null && !bitmap.isRecycled) {
                            albumArtCache[uri] = bitmap
                            runOnUiThread {
                                try {
                                    if (!isFinishing && !isDestroyed) {
                                        albumArtworkImageView.setImageBitmap(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error setting new bitmap", e)
                                }
                            }
                        }
                    }?.setErrorCallback { throwable ->
                        Log.e(TAG, "Failed to load image from Spotify: ${throwable.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading artwork", e)
                }
            }
        }
    }

    // Progress bar methods
    private fun setupProgressBar() {
        songProgressBar = findViewById(R.id.songProgressBar)
        songProgressBar.max = 1000
    }

    private fun startProgressBarUpdateSafe() {
        if (!isHost || isProgressBarUpdating) return
        
        isProgressBarUpdating = true
        updateProgressBarSafe()
    }

    private fun updateProgressBarSafe() {
        if (!isProgressBarUpdating || isFinishing || isDestroyed || !isHost) return

        try {
            spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                try {
                    val track = playerState?.track
                    if (track != null && !isFinishing && !isDestroyed) {
                        val songDuration = track.duration
                        val currentProgress = playerState.playbackPosition
                        val progress = ((currentProgress.toFloat() / songDuration.toFloat()) * 1000).toInt()

                        runOnUiThread {
                            try {
                                if (!isFinishing && !isDestroyed) {
                                    songProgressBar.progress = progress
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating progress bar UI", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in progress bar callback", e)
                }
            }?.setErrorCallback { throwable ->
                Log.e(TAG, "Error getting player state: ${throwable.message}")
            }

            mainHandler.postDelayed({
                updateProgressBarSafe()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up progress bar update", e)
        }
    }

    private fun stopProgressBarUpdateSafe() {
        isProgressBarUpdating = false
    }

    // Utility methods
    private fun showLoading(message: String) {
        try {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Error showing loading", e)
        }
    }

    private fun hideLoading() {
        try {
            loadingOverlay.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding loading", e)
        }
    }

    private fun showErrorToast(message: String) {
        try {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast", e)
        }
    }

    private fun vibrateSafe(durationMillis: Long = 50) {
        try {
            vibrate(durationMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHost) {
            startProgressBarUpdateSafe()
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressBarUpdateSafe()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        stopProgressBarUpdateSafe()
        mainHandler.removeCallbacksAndMessages(null)
        
        try {
            if (::spinningAnimator.isInitialized) {
                spinningAnimator.cancel()
            }
            stopAllPulseAnimations()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling animations", e)
        }
        
        coroutineScope.cancel()
        albumArtCache.clear()
        
        if (isHost) {
            SpotifyManager.disconnectSpotifyAppRemote()
        }
        
        wifiDirectManager.cleanup()
    }
}

fun Context.vibrate(durationMillis: Long = 50) {
    try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrationEffect = VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
                vibratorManager.defaultVibrator.vibrate(vibrationEffect)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val vibrationEffect = VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(vibrationEffect)
            }
            else -> {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(durationMillis)
            }
        }
    } catch (e: Exception) {
        Log.e("VibrationExtension", "Error vibrating", e)
    }
}