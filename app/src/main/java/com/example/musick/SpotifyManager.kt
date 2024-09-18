package com.example.musick

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume

object SpotifyManager {
    private const val CLIENT_ID = "41fe6d48712c4f7095829a361119ea07"
    private const val REDIRECT_URI = "com.example.musick://callback"
    private const val SPOTIFY_AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"

    var spotifyAppRemote: SpotifyAppRemote? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpirationTime: Long = 0
    private lateinit var codeVerifier: String

    private val gson = Gson()
    private val client = OkHttpClient()

    fun getAuthorizationUrl(): String {
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        return SPOTIFY_AUTH_URL +
                "?client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$codeChallenge" +
                "&scope=app-remote-control playlist-read-private"
    }

    suspend fun handleAuthorizationResponse(code: String, context: Context) {
        val tokenResponse = exchangeCodeForToken(code)
        accessToken = tokenResponse.accessToken
        refreshToken = tokenResponse.refreshToken
        tokenExpirationTime = System.currentTimeMillis() + tokenResponse.expiresIn * 1000

        saveTokens(context, tokenResponse)
    }

    fun isTokenValid(): Boolean {
        return accessToken != null && System.currentTimeMillis() < tokenExpirationTime
    }

    fun isConnected(): Boolean {
        return spotifyAppRemote?.isConnected == true
    }

    suspend fun reconnectIfNeeded(context: Context) {
        if (!isTokenValid()) {
            if (refreshToken != null) {
                refreshAccessToken(context)
            }
        }
    }

    private suspend fun exchangeCodeForToken(code: String): TokenResponse = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(SPOTIFY_TOKEN_URL)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response body")
        gson.fromJson(responseBody, TokenResponse::class.java)
    }

    private suspend fun refreshAccessToken(context: Context) = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken ?: throw IllegalStateException("Refresh token is null"))
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(SPOTIFY_TOKEN_URL)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response body")
        val tokenResponse = gson.fromJson(responseBody, TokenResponse::class.java)

        accessToken = tokenResponse.accessToken
        tokenExpirationTime = System.currentTimeMillis() + tokenResponse.expiresIn * 1000

        tokenResponse.refreshToken?.let {
            refreshToken = it
        }

        saveTokens(context, tokenResponse)
    }

    suspend fun connectToSpotifyAppRemote(context: Context): Boolean = withContext(Dispatchers.Main) {
        if (isConnected()) return@withContext true

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()

        try {
            suspendCancellableCoroutine<Boolean> { continuation ->
                SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        spotifyAppRemote = appRemote
                        Log.d("SpotifyManager", "Connected to Spotify App Remote")
                        continuation.resume(true)
                    }

                    override fun onFailure(throwable: Throwable) {
                        Log.e("SpotifyManager", "Failed to connect to Spotify App Remote", throwable)
                        continuation.resume(false)
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "Exception during Spotify App Remote connection", e)
            false
        }
    }

    private fun saveTokens(context: Context, tokenResponse: TokenResponse) {
        val sharedPreferences = context.getSharedPreferences("SpotifyTokens", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString("access_token", tokenResponse.accessToken)
            putString("refresh_token", tokenResponse.refreshToken)
            putLong("token_expiration_time", System.currentTimeMillis() + tokenResponse.expiresIn * 1000)
            apply()
        }
    }

    fun loadTokens(context: Context) {
        val sharedPreferences = context.getSharedPreferences("SpotifyTokens", Context.MODE_PRIVATE)
        accessToken = sharedPreferences.getString("access_token", null)
        refreshToken = sharedPreferences.getString("refresh_token", null)
        tokenExpirationTime = sharedPreferences.getLong("token_expiration_time", 0)
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

    fun disconnectSpotifyAppRemote() {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
    }

    fun getAccessToken(): String? = accessToken
}

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("refresh_token") val refreshToken: String?
)