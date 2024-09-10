package com.example.musick

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class MainActivity : AppCompatActivity() {

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private lateinit var playlistLinkInput: EditText
    private lateinit var startGameButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playlistLinkInput = findViewById(R.id.playlistLinkInput)
        startGameButton = findViewById(R.id.startGameButton)

        startGameButton.setOnClickListener { startGame() }
    }

    override fun onStart() {
        super.onStart()
        connectToSpotify()
    }

    private fun connectToSpotify() {
        try {
            Log.d("MainActivity", "Attempting to connect to Spotify")
            val connectionParams = ConnectionParams.Builder(clientId)
                .setRedirectUri(redirectUri)
                .showAuthView(true)
                .build()

            SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyAppRemote = appRemote
                    Log.d("MainActivity", "Connected to Spotify")
                    Toast.makeText(this@MainActivity, "Connected to Spotify", Toast.LENGTH_SHORT).show()
                    startGameButton.isEnabled = true
                }

                override fun onFailure(throwable: Throwable) {
                    Log.e("MainActivity", "Failed to connect to Spotify", throwable)
                    Toast.makeText(this@MainActivity, "Failed to connect to Spotify", Toast.LENGTH_LONG).show()
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Exception during Spotify connection attempt on Android ${android.os.Build.VERSION.RELEASE}", e)
            Toast.makeText(this@MainActivity, "Error initiating Spotify connection: ${e.message}", Toast.LENGTH_LONG).show()
        }

    }

    private fun startGame() {
        val playlistLink = playlistLinkInput.text.toString()
        val playlistId = extractPlaylistId(playlistLink)

        if (playlistId != null) {
            val intent = Intent(this, PlayerSetupActivity::class.java)
            intent.putExtra("PLAYLIST_ID", playlistId)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Invalid Spotify playlist link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractPlaylistId(playlistLink: String): String? {
        val regex = "playlist/([a-zA-Z0-9]+)".toRegex()
        val matchResult = regex.find(playlistLink)
        return matchResult?.groupValues?.get(1)
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}