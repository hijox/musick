package com.example.musick

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track

class MainActivity : AppCompatActivity() {

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private lateinit var playlistLinkInput: EditText
    private lateinit var startGameButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var skipButton: Button
    private lateinit var buzzerButton: Button
    private lateinit var songInfoText: TextView
    private lateinit var showHideInputButton: Button

    private var currentTrack: Track? = null
    private var isPlaying = false

    private lateinit var revealButton: Button
    private var revealButtonVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playlistLinkInput = findViewById(R.id.playlistLinkInput)
        startGameButton = findViewById(R.id.startGameButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        skipButton = findViewById(R.id.skipButton)
        buzzerButton = findViewById(R.id.buzzerButton)
        songInfoText = findViewById(R.id.songInfoText)
        showHideInputButton = findViewById(R.id.showHideInputButton)
        revealButton = findViewById(R.id.revealButton)
        revealButton.visibility = View.GONE

        startGameButton.setOnClickListener { startGame() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        skipButton.setOnClickListener { skipSong() }
        buzzerButton.setOnClickListener { activateBuzzer() }
        showHideInputButton.setOnClickListener { toggleInputVisibility() }
        revealButton.setOnClickListener { revealSongInfo() }

        playPauseButton.isEnabled = false
        skipButton.isEnabled = false
        buzzerButton.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        connectToSpotify()
    }

    private fun connectToSpotify() {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected to Spotify")
                Toast.makeText(this@MainActivity, "Connected to Spotify", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("MainActivity", "Failed to connect to Spotify", throwable)
                Toast.makeText(this@MainActivity, "Failed to connect to Spotify", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun startGame() {
        val playlistLink = playlistLinkInput.text.toString()
        val playlistId = extractPlaylistId(playlistLink)
        if (playlistId != null) {
            spotifyAppRemote?.playerApi?.let { playerApi ->
                playerApi.play("spotify:playlist:$playlistId")
                playerApi.setShuffle(true)  // Enable shuffle mode
            }
            isPlaying = true
            updatePlayPauseButtonText()
            playPauseButton.isEnabled = true
            skipButton.isEnabled = true
            buzzerButton.isEnabled = true
            startGameButton.isEnabled = false
            playlistLinkInput.isEnabled = false
            subscribeToChanges()
        } else {
            Toast.makeText(this, "Invalid Spotify playlist link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractPlaylistId(playlistLink: String): String? {
        val regex = "playlist/([a-zA-Z0-9]+)".toRegex()
        val matchResult = regex.find(playlistLink)
        return matchResult?.groupValues?.get(1)
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            spotifyAppRemote?.playerApi?.pause()
            isPlaying = false
        } else {
            spotifyAppRemote?.playerApi?.resume()
            isPlaying = true
        }
        updatePlayPauseButtonText()
    }

    private fun updatePlayPauseButtonText() {
        playPauseButton.text = if (isPlaying) "Pause" else "Play"
    }

    private fun skipSong() {
        spotifyAppRemote?.playerApi?.skipNext()
        resetLayout()
    }

    private fun activateBuzzer() {
        spotifyAppRemote?.playerApi?.pause()
        isPlaying = false
        updatePlayPauseButtonText()
        revealButton.visibility = View.VISIBLE
        revealButtonVisible = true
    }

    private fun revealSongInfo() {
        currentTrack?.let { track ->
            songInfoText.text = "Song: ${track.name}\nArtist: ${track.artist.name}"
        }
//        Handler(Looper.getMainLooper()).postDelayed({
//            playNextSong()
//        }, 5000)
    }

    private fun playNextSong() {
        spotifyAppRemote?.playerApi?.skipNext()
        resetLayout()
    }

    private fun resetLayout() {
        songInfoText.text = "Guess the song!"
        revealButton.visibility = View.GONE
        revealButtonVisible = false
        isPlaying = true
        updatePlayPauseButtonText()
    }

    private fun subscribeToChanges() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            playerState.track?.let { track ->
                if (track != currentTrack) {
                    currentTrack = track
                    if (!revealButtonVisible) {
                        songInfoText.text = "Guess the song!"
                    }
                }
            }
        }
    }

    private fun toggleInputVisibility() {
        if (playlistLinkInput.visibility == View.VISIBLE) {
            playlistLinkInput.visibility = View.GONE
            showHideInputButton.text = "Show Playlist Input"
        } else {
            playlistLinkInput.visibility = View.VISIBLE
            showHideInputButton.text = "Hide Playlist Input"
        }
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}