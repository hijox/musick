package com.example.musick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playlistLinkInput: EditText
    private lateinit var startGameButton: Button
    private lateinit var playlistHistoryRecyclerView: RecyclerView
    private lateinit var playlistHistoryAdapter: PlaylistHistoryAdapter

    private val playlistHistory = mutableListOf<PlaylistInfo>()

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private lateinit var loginContainer: FrameLayout
    private lateinit var loginButton: MaterialButton
    private lateinit var mainContent: ViewGroup
    private lateinit var loginRequiredMessage: TextView
    private lateinit var appDescriptionTextView: TextView
    private lateinit var loadingIndicator: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        setupLoginScreen()
        loadPlaylistHistory()
        setupPlaylistHistoryRecyclerView()

        SpotifyManager.loadTokens(this)
        checkAndRefreshToken()
        SpotifyManager.connectToSpotifyAppRemote(this)
    }

    override fun onResume() {
        super.onResume()
        checkAndRefreshToken()
    }

    private fun initializeViews() {
        playlistLinkInput = findViewById(R.id.playlistLinkInput)
        startGameButton = findViewById(R.id.startGameButton)
        playlistHistoryRecyclerView = findViewById(R.id.playlistHistoryRecyclerView)
        loginContainer = findViewById(R.id.loginContainer)
        loginButton = findViewById(R.id.loginButton)
        mainContent = findViewById(R.id.mainContent)
        loginRequiredMessage = findViewById(R.id.loginRequiredMessage)
        appDescriptionTextView = findViewById(R.id.appDescriptionTextView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
    }

    private fun setupClickListeners() {
        startGameButton.setOnClickListener { startGame() }
        loginButton.setOnClickListener { initiateSpotifyLogin() }
    }

    private fun setupLoginScreen() {
        appDescriptionTextView.text = getString(R.string.app_description)
        loginRequiredMessage.text = getString(R.string.login_required_message)
    }

    private fun checkAndRefreshToken() {
        showLoadingState()
        coroutineScope.launch {
            try {
                SpotifyManager.reconnectIfNeeded(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (SpotifyManager.isTokenValid()) {
                        showMainContent()
                    } else {
                        showLoginRequired()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to reconnect: ${e.message}", Toast.LENGTH_LONG).show()
                    showLoginRequired()
                }
            }
        }
    }

    private fun showLoadingState() {
        loginContainer.visibility = View.GONE
        mainContent.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE
    }

    private fun showLoginRequired() {
        loginContainer.visibility = View.VISIBLE
        mainContent.visibility = View.GONE
        loadingIndicator.visibility = View.GONE
    }

    private fun showMainContent() {
        loginContainer.visibility = View.GONE
        mainContent.visibility = View.VISIBLE
        loadingIndicator.visibility = View.GONE
    }

    private fun initiateSpotifyLogin() {
        val authUrl = SpotifyManager.getAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(authUrl)
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyCallback(intent)
    }

    private fun handleSpotifyCallback(intent: Intent) {
        val uri = intent.data
        if (uri?.scheme == "com.example.musick") {
            val code = uri.getQueryParameter("code")
            if (code != null) {
                handleSpotifyLogin(code)
            } else {
                val error = uri.getQueryParameter("error")
                Toast.makeText(this, "Authentication failed: $error", Toast.LENGTH_LONG).show()
                showLoginRequired()
            }
        }
    }

    private fun handleSpotifyLogin(code: String) {
        coroutineScope.launch {
            try {
                SpotifyManager.handleAuthorizationResponse(code, this@MainActivity)
                showMainContent()
                Toast.makeText(this@MainActivity, "Successfully logged in!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to get access token: ${e.message}", Toast.LENGTH_LONG).show()
                showLoginRequired()
            }
        }
    }

    private fun startGame() {
        if (!SpotifyManager.isTokenValid()) {
            Toast.makeText(this, "Please log in to Spotify first", Toast.LENGTH_SHORT).show()
            return
        }

        val playlistLink = playlistLinkInput.text.toString()
        val playlistId = extractPlaylistId(playlistLink)

        if (playlistId != null) {
            startGameWithPlaylist(playlistId, playlistLink)
        } else {
            Toast.makeText(this, "Invalid Spotify playlist link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGameWithPlaylist(playlistId: String, playlistLink: String) {
        if (!SpotifyManager.isTokenValid()) {
            checkAndRefreshToken()
            return
        }

        // Start PlayerSetupActivity immediately
        val intent = Intent(this@MainActivity, PlayerSetupActivity::class.java)
        intent.putExtra("PLAYLIST_ID", playlistId)
        startActivity(intent)

        // Fetch playlist name
        coroutineScope.launch {
            try {
                val playlistName = fetchPlaylistName(playlistId)
                addToPlaylistHistory(playlistId, playlistName, playlistLink)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to get Playlist Name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchPlaylistName(playlistId: String): String {
        return withContext(Dispatchers.IO) {
            val accessToken = SpotifyManager.getAccessToken() ?: throw IllegalStateException("Access token is null")
            val spotifyApi = SpotifyApiClient.create()
            val response = spotifyApi.getPlaylist("Bearer $accessToken", playlistId)
            response.name
        }
    }

    private fun addToPlaylistHistory(playlistId: String, playlistName: String, playlistLink: String) {
        val newPlaylist = PlaylistInfo(playlistId, playlistName, playlistLink)
        val existingIndex = playlistHistory.indexOfFirst { it.id == playlistId }

        if (existingIndex != -1) {
            playlistHistory.removeAt(existingIndex)
        }

        playlistHistory.add(0, newPlaylist)

        while (playlistHistory.size > 5) {
            playlistHistory.removeAt(playlistHistory.lastIndex)
        }

        savePlaylistHistory()
        playlistHistoryAdapter.notifyDataSetChanged()
    }

    private fun loadPlaylistHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val jsonHistory = sharedPref.getString("playlist_history", null)
        if (jsonHistory != null) {
            val type = object : TypeToken<List<PlaylistInfo>>() {}.type
            playlistHistory.clear()
            playlistHistory.addAll(Gson().fromJson(jsonHistory, type))
        }
    }

    private fun savePlaylistHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("playlist_history", Gson().toJson(playlistHistory))
            apply()
        }
    }

    private fun setupPlaylistHistoryRecyclerView() {
        playlistHistoryAdapter = PlaylistHistoryAdapter(playlistHistory) { playlistInfo ->
            startGameWithPlaylist(playlistInfo.id, playlistInfo.link)
        }
        playlistHistoryRecyclerView.layoutManager = LinearLayoutManager(this)
        playlistHistoryRecyclerView.adapter = playlistHistoryAdapter
    }

    private fun extractPlaylistId(playlistLink: String): String? {
        val regex = "playlist/([a-zA-Z0-9]+)".toRegex()
        val matchResult = regex.find(playlistLink)
        return matchResult?.groupValues?.get(1)
    }
}

data class PlaylistInfo(val id: String, val name: String, val link: String)

class PlaylistHistoryAdapter(
    private val playlists: List<PlaylistInfo>,
    private val onUseClick: (PlaylistInfo) -> Unit
) : RecyclerView.Adapter<PlaylistHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playlistName: TextView = view.findViewById(R.id.playlistName)
        val useButton: Button = view.findViewById(R.id.useButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.playlist_history_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.playlistName.text = playlist.name
        holder.useButton.setOnClickListener { onUseClick(playlist) }
    }

    override fun getItemCount() = playlists.size
}