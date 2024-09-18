package com.example.musick

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.LruCache
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnPause
import androidx.core.animation.doOnResume
import androidx.core.animation.doOnStart
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.spotify.protocol.types.Track
import com.spotify.protocol.types.ImageUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.musick.SpotifyManager.spotifyAppRemote
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class GameActivity : AppCompatActivity() {

    private lateinit var currentPlayerText: TextView
    private lateinit var guessSongText: TextView
    private lateinit var songNameText: TextView
    private lateinit var artistNameText: TextView
    private lateinit var albumArtworkImageView: ShapeableImageView
    private lateinit var buzzerButton: ShapeableImageView
    private lateinit var controlButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var playerScoresRecyclerView: RecyclerView
    private lateinit var songProgressBar: ProgressBar

    private var currentTrack: Track? = null
    private var currentPlayerIndex = 0
    private lateinit var playerNames: List<String>
    private var scores = mutableMapOf<String, Int>()
    private var isSongPaused = false
    private var isSongRevealed = false

    private lateinit var spinningAnimator: ValueAnimator
    private lateinit var progressAnimator: ObjectAnimator
    private var currentProgress: Long = 0
    private var lastRotation: Float = 0f

    private lateinit var loadingOverlay: View
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingText: TextView

    private var pendingPlaylistId: String? = null

    private var albumArtCache = mutableMapOf<ImageUri, Bitmap>()

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        pendingPlaylistId = intent.getStringExtra("PLAYLIST_ID")

        initializeViews()
        setupListeners()
        setupProgressAnimator()
        setupSpinningAnimation()

        showLoading("Connecting to Spotify")
        ensureSpotifyConnection()
    }

    override fun onResume() {
        super.onResume()
        // Reconnect to Spotify App if needed
        if (!SpotifyManager.isConnected()) {
            coroutineScope.launch {
                if (!SpotifyManager.isConnected()) {
                    val connected = SpotifyManager.connectToSpotifyAppRemote(this@GameActivity)
                    if (!connected) {
                        Toast.makeText(
                            this@GameActivity,
                            "Failed to connect to Spotify",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@launch
                    }
                }
                getTrackInfos { track, _, albumCoverImageUri, _ ->
                    currentTrack = track
                    preloadAlbumArtwork(albumCoverImageUri)
                    updateProgressBar()
                }
            }
        }
    }

    private fun initializeViews() {
        currentPlayerText = findViewById(R.id.currentPlayerText)
        buzzerButton = findViewById(R.id.buzzerButton)
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
            if (!SpotifyManager.isConnected()) {
                val connected = SpotifyManager.connectToSpotifyAppRemote(this@GameActivity)
                if (!connected) {
                    Toast.makeText(this@GameActivity, "Failed to connect to Spotify", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
            setupGame()
            pendingPlaylistId?.let { playlistId ->
                playPlaylist(playlistId)
            }
            hideLoading()
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
        playerScoresRecyclerView.layoutManager = LinearLayoutManager(this)
        playerScoresRecyclerView.adapter = PlayerScoreAdapter(scores) { player, points ->
            updatePlayerScore(player, points)
        }
    }

    private fun setupListeners() {
        buzzerButton.setOnClickListener {
            vibrate(100)
            toggleBuzzer()
        }
        controlButton.setOnClickListener {
            handleControlButtonClick()
        }
        skipButton.setOnClickListener {
            handleSkipButtonClick()
        }
    }

    private fun setupSpinningAnimation() {
        spinningAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000 // 3 seconds for a full rotation
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val rotation = animator.animatedValue as Float
                buzzerButton.rotation = rotation
                lastRotation = rotation
            }
            doOnPause {
                // Store the current rotation when paused
                lastRotation = buzzerButton.rotation
            }
            doOnResume {
                // Start from the last rotation when resumed
                setFloatValues(lastRotation, lastRotation + 360f)
            }
            doOnCancel {
                buzzerButton.rotation = 0f
            }
            doOnEnd {
                buzzerButton.rotation = 0f
            }
        }
    }

    private fun setupProgressAnimator() {
        progressAnimator = ObjectAnimator.ofInt(songProgressBar, "progress", 0, 100).apply {
            duration = 30000 // Default duration, will be updated with actual track length
        }
    }

    private fun getTrackInfos(callback: (Track?, String, ImageUri?, Long) -> Unit) {
        spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
            val track = playerState.track
            val artistName = track?.artist?.name ?: ""
            val albumCoverImageUri = track?.imageUri
            val progress = playerState.playbackPosition

            callback(track, artistName, albumCoverImageUri, progress)
        }
    }

    private fun startSong() {
        if (isSongPaused) {
            spotifyAppRemote?.playerApi?.resume()
        }
        isSongPaused = false
        startSpinningAnimation()
        updateButtonStates()

        getTrackInfos { track, _, albumCoverImageUri, _ ->
            currentTrack = track
            preloadAlbumArtwork(albumCoverImageUri)
            updateProgressBar()
        }
    }

    private fun pauseSong() {
        spotifyAppRemote?.playerApi?.pause()
        isSongPaused = true
        pauseSpinningAnimation()
        updateButtonStates()
        progressAnimator.pause()
    }

    private fun playPlaylist(playlistId: String) {
        spotifyAppRemote?.playerApi?.let { playerApi ->
            playerApi.setShuffle(true)
            playerApi.play("spotify:playlist:$playlistId")
            startSong()
        }
    }

    private fun toggleBuzzer() {
        if (!isSongRevealed) {
            if (isSongPaused) {
                startSong()
            } else {
                pauseSong()
            }
        }
    }

    private fun handleControlButtonClick() {
        if (isSongRevealed) {
            nextTurn()
        } else if (isSongPaused) {
            revealSongInfo()
        }
    }

    private fun handleSkipButtonClick() {
        skipSong()
    }

    private fun revealSongInfo() {
        getTrackInfos { track, artistName, albumCoverImageUri, _ ->
            currentTrack = track
            runOnUiThread {
                guessSongText.visibility = View.GONE
                songNameText.apply {
                    text = track?.name
                    visibility = View.VISIBLE
                }
                artistNameText.apply {
                    text = artistName
                    visibility = View.VISIBLE
                }
                transformBuzzerToAlbumCover(albumCoverImageUri)
            }
        }
        isSongRevealed = true
        updateButtonStates()
    }

    private fun transformBuzzerToAlbumCover(albumCoverImageUri: ImageUri?) {
        val fadeOut = ObjectAnimator.ofFloat(buzzerButton, "alpha", 1f, 0f)
        val fadeIn = ObjectAnimator.ofFloat(albumArtworkImageView, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(fadeOut, fadeIn)
            duration = 500
            doOnStart {
                loadAlbumArtwork(albumCoverImageUri)
                albumArtworkImageView.visibility = View.VISIBLE
            }
            doOnEnd {
                buzzerButton.visibility = View.GONE
            }
            start()
        }
    }

    private fun transformAlbumCoverToBuzzer() {
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
    }

    private fun preloadAlbumArtwork(imageUri: ImageUri?) {
        imageUri?.let { uri ->
            if (albumArtCache[uri] == null) {
                spotifyAppRemote?.imagesApi?.getImage(imageUri)?.setResultCallback { bitmap ->
                    albumArtCache[uri] = bitmap
                }
            }
        }
    }

    private fun loadAlbumArtwork(imageUri: ImageUri?) {
        imageUri?.let { uri ->
            val cachedBitmap = albumArtCache[uri]
            if (cachedBitmap != null) {
                runOnUiThread {
                    albumArtworkImageView.setImageBitmap(cachedBitmap)
                }
            } else {
                // If the image is not in cache, retrieve it from Spotify's imagesApi
                spotifyAppRemote?.imagesApi?.getImage(imageUri)?.setResultCallback { bitmap ->
                    runOnUiThread {
                        albumArtworkImageView.setImageBitmap(bitmap)
                    }
                }?.setErrorCallback { throwable ->
                    // Handle any errors here, such as logging or displaying a placeholder
                    Log.e("SpotifyImages", "Failed to load image from Spotify: ${throwable.message}")
                }
            }
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
        spotifyAppRemote?.playerApi?.skipNext()
        startSong()
    }

    private fun updateCurrentPlayer() {
        currentPlayerText.text = "${playerNames[currentPlayerIndex]}"
    }

    private fun resetForNewSong() {
        transformAlbumCoverToBuzzer()
        isSongPaused = false
        isSongRevealed = false
        guessSongText.visibility = View.VISIBLE
        songNameText.visibility = View.GONE
        artistNameText.visibility = View.GONE
        // Clear albumArtCache
        albumArtCache.clear()
        updateButtonStates()
        resetProgressBar()
    }

    private fun updateButtonStates() {
        buzzerButton.isEnabled = !isSongRevealed

        when {
            isSongRevealed -> {
                controlButton.visibility = View.VISIBLE
                albumArtworkImageView.visibility = View.VISIBLE
                controlButton.text = "Next Turn"
                skipButton.isEnabled = false
            }
            isSongPaused -> {
                controlButton.visibility = View.VISIBLE
                controlButton.text = "Reveal Song"
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
    }

    private fun updatePlayerScore(player: String, points: Int) {
        scores[player] = scores[player]!! + points
        playerScoresRecyclerView.adapter?.notifyDataSetChanged()
    }

    private fun startSpinningAnimation() {
        try {
            if (spinningAnimator.isPaused == true) {
                spinningAnimator.resume()
            } else {
                spinningAnimator.start()
            }
        } catch (e: Exception) {
            Log.e("GameActivity", "Error starting spinning animation", e)
        }
    }

    private fun pauseSpinningAnimation() {
        try {
            spinningAnimator.pause()
        } catch (e: Exception) {
            Log.e("GameActivity", "Error pausing spinning animation", e)
        }
    }

    private fun updateProgressBar() {
        getTrackInfos { track, _, _, progress ->
            currentTrack = track
            val duration = track?.duration ?: 30000
            progressAnimator.duration = duration
            progressAnimator.setCurrentFraction(progress.toFloat() / duration)
            if (!isSongPaused) {
                progressAnimator.start()
            }
        }
    }

    private fun resetProgressBar() {
        progressAnimator.cancel()
        songProgressBar.progress = 0
        currentProgress = 0
    }

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
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

fun Context.vibrate(durationMillis: Long = 50) {
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
}