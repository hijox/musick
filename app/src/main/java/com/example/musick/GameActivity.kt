package com.example.musick

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.spotify.protocol.types.Track
import com.spotify.protocol.types.ImageUri
import com.example.musick.SpotifyManager.spotifyAppRemote
import com.google.android.material.imageview.ShapeableImageView
import com.example.musick.utils.SafeHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class GameActivity : AppCompatActivity() {

    private lateinit var currentPlayerText: TextView
    private lateinit var guessSongText: TextView
    private lateinit var songNameText: TextView
    private lateinit var artistNameText: TextView
    private lateinit var albumArtworkImageView: ShapeableImageView
    private lateinit var controlButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var playerScoresRecyclerView: RecyclerView
    private lateinit var buzzerButton: ShapeableImageView
    private lateinit var playIcon: ShapeableImageView
    private lateinit var pauseIcon: ShapeableImageView

    private var currentTrack: Track? = null
    private var currentPlayerIndex = 0
    private lateinit var playerNames: List<String>
    private var scores = mutableMapOf<String, Int>()
    private var isSongPaused = false
    private var isSongRevealed = false

    private lateinit var spinningAnimator: ValueAnimator
    private var lastRotation: Float = 0f

    private lateinit var songProgressBar: ProgressBar
    private var isProgressBarUpdating = false

    private lateinit var loadingOverlay: View
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingText: TextView

    private var pendingPlaylistId: String? = null

    private var albumArtCache = mutableMapOf<ImageUri, Bitmap>()

    private val safeHandler = SafeHandler(this)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        pendingPlaylistId = intent.getStringExtra("PLAYLIST_ID")

        initializeViews()
        setupListeners()
        setupProgressBar()
        setupSpinningAnimation()

        showLoading("Connecting to Spotify")
        ensureSpotifyConnection()
    }

    override fun onResume() {
        super.onResume()
        if (!SpotifyManager.isConnected()) {
            coroutineScope.launch {
                try {
                    val connected = SpotifyManager.connectToSpotifyAppRemote(this@GameActivity)
                    if (!connected) {
                        showErrorToast("Failed to connect to Spotify")
                        finish()
                        return@launch
                    }
                    startProgressBarUpdateSafe()
                    preloadCurrentTrackArt()
                } catch (e: Exception) {
                    Log.e("GameActivity", "Resume connection error", e)
                    showErrorToast("Connection error: ${e.message}")
                }
            }
        } else {
            startProgressBarUpdateSafe()
            preloadCurrentTrackArt()
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressBarUpdateSafe()
        cleanupSpotifySubscriptions()
    }

    private fun initializeViews() {
        currentPlayerText = findViewById(R.id.currentPlayerText)
        buzzerButton = findViewById(R.id.buzzerButton)
        playIcon = findViewById(R.id.playIcon)
        pauseIcon = findViewById(R.id.pauseIcon)
        albumArtworkImageView = findViewById(R.id.albumArtworkImageView)
        guessSongText = findViewById(R.id.guessSongText)
        songNameText = findViewById(R.id.songNameText)
        artistNameText = findViewById(R.id.artistNameText)
        controlButton = findViewById(R.id.controlButton)
        skipButton = findViewById(R.id.skipButton)
        playerScoresRecyclerView = findViewById(R.id.playerScoresRecyclerView)
        songProgressBar = findViewById(R.id.songProgressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        loadingText = findViewById(R.id.loadingText)
    }

    private fun ensureSpotifyConnection() {
        coroutineScope.launch {
            try {
                if (!SpotifyManager.isConnected()) {
                    val connected = SpotifyManager.connectToSpotifyAppRemote(this@GameActivity)
                    if (!connected) {
                        showErrorToast("Failed to connect to Spotify")
                        finish()
                        return@launch
                    }
                }
                setupGame()
                pendingPlaylistId?.let { playlistId ->
                    playPlaylist(playlistId)
                }
                hideLoading()
            } catch (e: Exception) {
                Log.e("GameActivity", "Connection error", e)
                showErrorToast("Connection failed: ${e.message}")
                finish()
            }
        }
    }

    private fun setupGame() {
        playerNames = intent.getStringArrayListExtra("PLAYER_NAMES") ?: listOf()
        scores = playerNames.associateWith { 0 }.toMutableMap()
        updateCurrentPlayer()
        setupScoresRecyclerView()
        updateButtonStates()
    }

    private fun setupScoresRecyclerView() {
        try {
            playerScoresRecyclerView.layoutManager = LinearLayoutManager(this)
            playerScoresRecyclerView.adapter = PlayerScoreAdapter(scores) { player, points ->
                updatePlayerScore(player, points)
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error setting up scores RecyclerView", e)
        }
    }

    private fun setupListeners() {
        buzzerButton.setOnClickListener {
            vibrateSafe(100)
            handleBuzzerButtonClick()
        }
        albumArtworkImageView.setOnClickListener {
            vibrateSafe(100)
            handleAlbumCoverClick()
        }
        controlButton.setOnClickListener {
            handleControlButtonClick()
        }
        skipButton.setOnClickListener {
            handleSkipButtonClick()
        }
    }

    private fun getTrackInfosSafe(callback: (Track?, String, ImageUri?, Long) -> Unit) {
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
                    Log.e("GameActivity", "Error processing track info", e)
                }
            }?.setErrorCallback { throwable ->
                Log.e("GameActivity", "Error getting track info", throwable)
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error setting up track info callback", e)
        }
    }

    private fun startSong() {
        try {
            if (isSongPaused) {
                spotifyAppRemote?.playerApi?.resume()
            }
            isSongPaused = false
            pauseIcon.visibility = View.VISIBLE
            playIcon.visibility = View.GONE
            startSpinningAnimation()
            updateButtonStates()
            startProgressBarUpdateSafe()

            preloadCurrentTrackArt()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error starting song", e)
            showErrorToast("Failed to start song")
        }
    }

    private fun pauseSong() {
        try {
            spotifyAppRemote?.playerApi?.pause()
            isSongPaused = true
            pauseIcon.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            pauseSpinningAnimation()
            updateButtonStates()
            stopProgressBarUpdateSafe()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error pausing song", e)
            showErrorToast("Failed to pause song")
        }
    }

    private fun playPlaylist(playlistId: String) {
        try {
            spotifyAppRemote?.playerApi?.let { playerApi ->
                playerApi.setShuffle(true)
                playerApi.play("spotify:playlist:$playlistId")
                startSong()
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error playing playlist", e)
            showErrorToast("Failed to play playlist")
        }
    }

    private fun handleBuzzerButtonClick() {
        if (!isSongRevealed) {
            if (isSongPaused) {
                startSong()
            } else {
                pauseSong()
            }
        }
    }

    private fun handleAlbumCoverClick() {
        if (isSongRevealed) {
            nextTurn()
        }
    }

    private fun handleControlButtonClick() {
        if (isSongPaused) {
            revealSongInfo()
        }
    }

    private fun handleSkipButtonClick() {
        skipSong()
    }

    private fun revealSongInfo() {
        getTrackInfosSafe { track, artistName, albumCoverImageUri, _ ->
            currentTrack = track
            runOnUiThread {
                try {
                    guessSongText.visibility = View.GONE
                    songNameText.apply {
                        text = track?.name ?: "Unknown Song"
                        visibility = View.VISIBLE
                    }
                    artistNameText.apply {
                        text = artistName
                        visibility = View.VISIBLE
                    }
                    transformBuzzerToAlbumCover(albumCoverImageUri)
                } catch (e: Exception) {
                    Log.e("GameActivity", "Error updating UI in reveal", e)
                }
            }
        }
        isSongRevealed = true
        updateButtonStates()
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
            Log.e("GameActivity", "Error in buzzer to album animation", e)
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
            Log.e("GameActivity", "Error in album to buzzer animation", e)
            albumArtworkImageView.visibility = View.GONE
            buzzerButton.visibility = View.VISIBLE
        }
    }

    private fun preloadCurrentTrackArt() {
        getTrackInfosSafe { _, _, albumCoverImageUri, _ ->
            preloadAlbumArtwork(albumCoverImageUri)
        }
    }

    private fun preloadAlbumArtwork(imageUri: ImageUri?) {
        imageUri?.let { uri ->
            if (albumArtCache[uri] == null) {
                try {
                    spotifyAppRemote?.imagesApi?.getImage(imageUri)?.setResultCallback { bitmap ->
                        albumArtCache[uri] = bitmap
                    }?.setErrorCallback { throwable ->
                        Log.e("GameActivity", "Failed to preload image: ${throwable.message}")
                    }
                } catch (e: Exception) {
                    Log.e("GameActivity", "Error preloading artwork", e)
                }
            }
        }
    }

    private fun loadAlbumArtworkSafe(imageUri: ImageUri?) {
        imageUri?.let { uri ->
            val cachedBitmap = albumArtCache[uri]
            if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                runOnUiThread {
                    try {
                        if (!isFinishing && !isDestroyed) {
                            albumArtworkImageView.setImageBitmap(cachedBitmap)
                        }
                    } catch (e: Exception) {
                        Log.e("GameActivity", "Error setting cached bitmap", e)
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
                                    Log.e("GameActivity", "Error setting new bitmap", e)
                                }
                            }
                        }
                    }?.setErrorCallback { throwable ->
                        Log.e("GameActivity", "Failed to load image from Spotify: ${throwable.message}")
                    }
                } catch (e: Exception) {
                    Log.e("GameActivity", "Error loading artwork", e)
                }
            }
        }
    }

    private fun skipSong() {
        resetForNewSong()
        try {
            spotifyAppRemote?.playerApi?.skipNext()
            startSong()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error skipping song", e)
            showErrorToast("Failed to skip song")
        }
    }

    private fun nextTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerNames.size
        updateCurrentPlayer()
        resetForNewSong()
        try {
            spotifyAppRemote?.playerApi?.skipNext()
            startSong()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error in next turn", e)
            showErrorToast("Failed to skip to next song")
        }
    }

    private fun updateCurrentPlayer() {
        try {
            if (playerNames.isNotEmpty()) {
                currentPlayerText.text = playerNames[currentPlayerIndex]
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error updating current player", e)
        }
    }

    private fun resetForNewSong() {
        // Stop animation cleanly
        pauseSpinningAnimation()

        // Reset game state
        transformAlbumCoverToBuzzer()
        isSongPaused = false
        isSongRevealed = false
        guessSongText.visibility = View.VISIBLE
        songNameText.visibility = View.GONE
        artistNameText.visibility = View.GONE
        albumArtCache.clear()
        stopProgressBarUpdateSafe()
        songProgressBar.progress = 0

        // Reset rotation to 0 for new song and restart animation
        lastRotation = 0f
        buzzerButton.rotation = 0f

        updateButtonStates()
    }

    private fun updateButtonStates() {
        try {
            buzzerButton.isEnabled = !isSongRevealed

            when {
                isSongRevealed -> {
                    controlButton.visibility = View.INVISIBLE
                    albumArtworkImageView.visibility = View.VISIBLE
                    skipButton.isEnabled = false
                }
                isSongPaused -> {
                    controlButton.visibility = View.VISIBLE
                    controlButton.text = "Reveal"
                    albumArtworkImageView.visibility = View.GONE
                    skipButton.isEnabled = false
                    pauseSpinningAnimation()
                }
                else -> {
                    controlButton.visibility = View.INVISIBLE
                    albumArtworkImageView.visibility = View.GONE
                    skipButton.isEnabled = true
                    startSpinningAnimation()
                }
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error updating button states", e)
        }
    }

    private fun updatePlayerScore(player: String, points: Int) {
        try {
            val currentScore = scores[player] ?: 0
            scores[player] = currentScore + points
            playerScoresRecyclerView.adapter?.notifyDataSetChanged()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error updating player score", e)
        }
    }

    private fun setupSpinningAnimation() {
        try {
            spinningAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3000 // 3 seconds for a full rotation
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
                        Log.e("GameActivity", "Error in animation update", e)
                    }
                }
                doOnCancel {
                    if (!isFinishing && !isDestroyed) {
                        lastRotation = buzzerButton.rotation
                    }
                }
                doOnEnd {
                    if (!isFinishing && !isDestroyed) {
                        lastRotation = buzzerButton.rotation
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error setting up spinning animation", e)
        }
    }

    private fun startSpinningAnimation() {
        try {
            if (!::spinningAnimator.isInitialized) {
                setupSpinningAnimation()
            }

            if (spinningAnimator.isRunning) {
                // Already running, no need to restart
                return
            }

            // Get current rotation and normalize it to 0-360 range
            val currentRotation = buzzerButton.rotation % 360f
            val normalizedRotation = if (currentRotation < 0) currentRotation + 360f else currentRotation

            // Cancel any existing animation
            spinningAnimator.cancel()

            // Start from current position and continue smoothly
            spinningAnimator.setFloatValues(normalizedRotation, normalizedRotation + 360f)
            spinningAnimator.start()

            Log.d("GameActivity", "Started spinning from ${normalizedRotation} degrees")
        } catch (e: Exception) {
            Log.e("GameActivity", "Error starting spinning animation", e)
        }
    }

    private fun pauseSpinningAnimation() {
        try {
            if (::spinningAnimator.isInitialized && spinningAnimator.isRunning) {
                // Store the exact current rotation
                lastRotation = buzzerButton.rotation
                spinningAnimator.cancel()

                // Keep the rotation at the exact position where we stopped
                buzzerButton.rotation = lastRotation

                Log.d("GameActivity", "Paused spinning at ${lastRotation} degrees")
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error pausing spinning animation", e)
        }
    }

    private fun setupProgressBar() {
        songProgressBar = findViewById(R.id.songProgressBar)
        songProgressBar.max = 1000
    }

    private fun startProgressBarUpdateSafe() {
        if (!isProgressBarUpdating) {
            isProgressBarUpdating = true
            updateProgressBarSafe()
        }
    }

    private fun stopProgressBarUpdateSafe() {
        isProgressBarUpdating = false
        safeHandler.removeCallbacks()
        cleanupSpotifySubscriptions()
    }

    private fun updateProgressBarSafe() {
        if (!isProgressBarUpdating || isFinishing || isDestroyed) return

        try {
            cleanupSpotifySubscriptions()

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
                                Log.e("GameActivity", "Error updating progress bar UI", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GameActivity", "Error in progress bar callback", e)
                }
            }?.setErrorCallback { throwable ->
                Log.e("GameActivity", "Error getting player state: ${throwable.message}")
            }

            safeHandler.post(1000) { updateProgressBarSafe() }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error setting up progress bar update", e)
        }
    }

    private fun cleanupSpotifySubscriptions() {
        try {
            safeHandler.removeCallbacks()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error cleaning up callbacks", e)
        }
    }

    private fun showLoading(message: String) {
        try {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("GameActivity", "Error showing loading", e)
        }
    }

    private fun hideLoading() {
        try {
            loadingOverlay.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("GameActivity", "Error hiding loading", e)
        }
    }

    private fun showErrorToast(message: String) {
        try {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error showing toast", e)
        }
    }

    private fun vibrateSafe(durationMillis: Long = 50) {
        try {
            vibrate(durationMillis)
        } catch (e: Exception) {
            Log.e("GameActivity", "Error vibrating", e)
        }
    }

    override fun onStop() {
        super.onStop()
        stopProgressBarUpdateSafe()
        cleanupSpotifySubscriptions()
        SpotifyManager.disconnectSpotifyAppRemote()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressBarUpdateSafe()
        cleanupSpotifySubscriptions()
        safeHandler.removeCallbacks()

        try {
            if (::spinningAnimator.isInitialized) {
                spinningAnimator.cancel()
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error cancelling animation", e)
        }

        coroutineScope.cancel()
        albumArtCache.clear()
        SpotifyManager.disconnectSpotifyAppRemote()
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
        try {
            val player = scores.keys.elementAt(position)
            val score = scores[player] ?: 0

            holder.playerNameText.text = player
            holder.scoreText.text = score.toString()

            val maxScore = scores.values.maxOrNull() ?: 0

            if (score == maxScore && score > 0) {
                holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
                holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
            } else {
                holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.white))
                holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.white))
            }

            holder.minusOneButton.setOnClickListener {
                try {
                    onScoreChange(player, -1)
                } catch (e: Exception) {
                    Log.e("PlayerScoreAdapter", "Error decreasing score", e)
                }
            }
            holder.plusOneButton.setOnClickListener {
                try {
                    onScoreChange(player, 1)
                } catch (e: Exception) {
                    Log.e("PlayerScoreAdapter", "Error increasing score", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerScoreAdapter", "Error binding view holder", e)
        }
    }

    override fun getItemCount() = scores.size
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
