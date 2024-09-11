package com.example.musick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private val clientId = "41fe6d48712c4f7095829a361119ea07"
    private val redirectUri = "com.example.musick://callback"

    private lateinit var playlistLinkInput: EditText
    private lateinit var startGameButton: Button
    private lateinit var playlistHistoryRecyclerView: RecyclerView
    private lateinit var playlistHistoryAdapter: PlaylistHistoryAdapter

    private val playlistHistory = mutableListOf<PlaylistInfo>()

    private lateinit var spotifyApi: SpotifyApi
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private lateinit var codeVerifier: String
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpirationTime: Long = 0

    private lateinit var loginContainer: FrameLayout
    private lateinit var loginButton: MaterialButton
    private lateinit var mainContent: ViewGroup
    private lateinit var loginRequiredMessage: TextView
    private lateinit var appDescriptionTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playlistLinkInput = findViewById(R.id.playlistLinkInput)
        startGameButton = findViewById(R.id.startGameButton)
        playlistHistoryRecyclerView = findViewById(R.id.playlistHistoryRecyclerView)

        loginContainer = findViewById(R.id.loginContainer)
        loginButton = findViewById(R.id.loginButton)
        mainContent = findViewById(R.id.mainContent)
        loginRequiredMessage = findViewById(R.id.loginRequiredMessage)
        appDescriptionTextView = findViewById(R.id.appDescriptionTextView)

        startGameButton.setOnClickListener { startGame() }
        loginButton.setOnClickListener { initiateSpotifyLogin() }

        setupLoginScreen()
        loadPlaylistHistory()
        setupPlaylistHistoryRecyclerView()
        setupSpotifyApi()

        loadTokens()
        checkAndRefreshToken()
    }

    override fun onResume() {
        super.onResume()
        checkAndRefreshToken()
    }

    private fun setupLoginScreen() {
        appDescriptionTextView.text = getString(R.string.app_description)
        loginRequiredMessage.text = getString(R.string.login_required_message)
    }

    private fun checkAndRefreshToken() {
        if (!isTokenValid()) {
            if (refreshToken != null) {
                refreshAccessToken { success ->
                    runOnUiThread {
                        if (success) {
                            showMainContent()
                        } else {
                            showLoginRequired()
                        }
                    }
                }
            } else {
                showLoginRequired()
            }
        } else {
            showMainContent()
        }
    }

    private fun showLoginRequired() {
        loginContainer.visibility = View.VISIBLE
        mainContent.visibility = View.GONE
    }

    private fun showMainContent() {
        loginContainer.visibility = View.GONE
        mainContent.visibility = View.VISIBLE
    }

    private fun setupSpotifyApi() {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.spotify.com/v1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        spotifyApi = retrofit.create(SpotifyApi::class.java)
    }

    private fun initiateSpotifyLogin() {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        this.codeVerifier = codeVerifier

        val authUrl = "https://accounts.spotify.com/authorize" +
                "?client_id=$clientId" +
                "&response_type=code" +
                "&redirect_uri=${Uri.encode(redirectUri)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$codeChallenge" +
                "&scope=app-remote-control playlist-read-private"

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(this, Uri.parse(authUrl))
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
                exchangeCodeForToken(code)
            } else {
                val error = uri.getQueryParameter("error")
                Toast.makeText(this, "Authentication failed: $error", Toast.LENGTH_LONG).show()
                showLoginRequired()
            }
        }
    }

    private fun exchangeCodeForToken(code: String) {
        coroutineScope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val requestBody = FormBody.Builder()
                        .add("client_id", clientId)
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", redirectUri)
                        .add("code_verifier", codeVerifier)
                        .build()

                    val request = Request.Builder()
                        .url("https://accounts.spotify.com/api/token")
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    Gson().fromJson(responseBody, TokenResponse::class.java)
                }

                accessToken = tokenResponse.accessToken
                refreshToken = tokenResponse.refreshToken
                tokenExpirationTime = System.currentTimeMillis() + tokenResponse.expiresIn * 1000

                tokenResponse.refreshToken?.let {
                    saveTokens(tokenResponse.accessToken, it, tokenResponse.expiresIn)
                }

                showMainContent()

                Toast.makeText(this@MainActivity, "Successfully logged in!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to get access token: ${e.message}", Toast.LENGTH_LONG).show()
                showLoginRequired()
            }
        }
    }

    private fun refreshAccessToken(callback: (Boolean) -> Unit) {
        coroutineScope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val requestBody = FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken ?: "")
                        .add("client_id", clientId)
                        .build()

                    val request = Request.Builder()
                        .url("https://accounts.spotify.com/api/token")
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    Gson().fromJson(responseBody, TokenResponse::class.java)
                }

                accessToken = tokenResponse.accessToken
                tokenExpirationTime = System.currentTimeMillis() + tokenResponse.expiresIn * 1000

                if (tokenResponse.refreshToken != null) {
                    refreshToken = tokenResponse.refreshToken
                }

                saveTokens(tokenResponse.accessToken, refreshToken ?: "", tokenResponse.expiresIn)
                callback(true)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to refresh token: ${e.message}")
                callback(false)
            }
        }
    }

    private fun startGame() {
        if (!isTokenValid()) {
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
        if (!isTokenValid()) {
            checkAndRefreshToken()
            return
        }

        // Start PlayerSetupActivity immediately
        val intent = Intent(this@MainActivity, PlayerSetupActivity::class.java)
        intent.putExtra("PLAYLIST_ID", playlistId)
        startActivity(intent)

        // Fetch playlist name asynchronously
        coroutineScope.launch {
            try {
                val playlistName = fetchPlaylistName(playlistId)
                addToPlaylistHistory(playlistId, playlistName, playlistLink)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to fetch playlist info: ${e.message}")
            }
        }
    }

    private fun loadTokens() {
        val sharedPreferences = getSharedPreferences("SpotifyTokens", Context.MODE_PRIVATE)
        accessToken = sharedPreferences.getString("access_token", null)
        refreshToken = sharedPreferences.getString("refresh_token", null)
        tokenExpirationTime = sharedPreferences.getLong("token_expiration_time", 0)
    }

    private fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val sharedPreferences = getSharedPreferences("SpotifyTokens", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString("access_token", accessToken)
            putString("refresh_token", refreshToken)
            putLong("token_expiration_time", System.currentTimeMillis() + expiresIn * 1000)
            apply()
        }
    }

    private fun isTokenValid(): Boolean {
        return accessToken != null && System.currentTimeMillis() < tokenExpirationTime
    }

    private suspend fun fetchPlaylistName(playlistId: String): String {
        return withContext(Dispatchers.IO) {
            val response = spotifyApi.getPlaylist("Bearer $accessToken", playlistId)
            response.name
        }
    }

    private fun addToPlaylistHistory(playlistId: String, playlistName: String, playlistLink: String) {
        val newPlaylist = PlaylistInfo(playlistId, playlistName, playlistLink)

        // Check if the playlist already exists in the history
        val existingIndex = playlistHistory.indexOfFirst { it.id == playlistId }

        if (existingIndex != -1) {
            // If the playlist exists, remove it from its current position
            playlistHistory.removeAt(existingIndex)
        }

        // Add the playlist to the top of the list
        playlistHistory.add(0, newPlaylist)

        // Ensure the history doesn't exceed 5 items
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

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val codeVerifier = ByteArray(32)
        secureRandom.nextBytes(codeVerifier)
        return Base64.encodeToString(codeVerifier, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun extractPlaylistId(playlistLink: String): String? {
        val regex = "playlist/([a-zA-Z0-9]+)".toRegex()
        val matchResult = regex.find(playlistLink)
        return matchResult?.groupValues?.get(1)
    }
}

interface SpotifyApi {
    @GET("playlists/{playlist_id}")
    suspend fun getPlaylist(
        @Header("Authorization") auth: String,
        @Path("playlist_id") playlistId: String
    ): PlaylistResponse
}

data class PlaylistResponse(
    @SerializedName("name") val name: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("refresh_token") val refreshToken: String?
)

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