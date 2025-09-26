package com.example.musick

import android.util.Log
import com.example.musick.utils.Result
import com.google.gson.annotations.SerializedName
import com.spotify.android.appremote.BuildConfig
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object SpotifyApiClient {
    private const val BASE_URL = "https://api.spotify.com/v1/"
    private const val TAG = "SpotifyApiClient"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 1000L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.NONE
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(original.method, original.body)
            chain.proceed(requestBuilder.build())
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api: SpotifyApi = retrofit.create(SpotifyApi::class.java)

    suspend fun getPlaylistSafe(accessToken: String, playlistId: String): Result<PlaylistResponse> {
        return executeWithRetry {
            val response = api.getPlaylist("Bearer $accessToken", playlistId)
            validatePlaylistResponse(response)
            response
        }
    }

    private suspend fun <T> executeWithRetry(
        maxRetries: Int = MAX_RETRIES,
        operation: suspend () -> T
    ): Result<T> {
        repeat(maxRetries) { attempt ->
            try {
                val result = operation()
                return Result.Success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}", e)

                val shouldRetry = when (e) {
                    is IOException, is SocketTimeoutException -> true
                    is HttpException -> e.code() in 500..599 || e.code() == 429 // Server errors or rate limiting
                    else -> false
                }

                if (!shouldRetry || attempt == maxRetries - 1) {
                    return Result.Error(e, getErrorMessage(e))
                }

                // Exponential backoff
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return Result.Error(
            IllegalStateException("Max retries exceeded"),
            "Failed to complete request after $maxRetries attempts"
        )
    }

    private fun validatePlaylistResponse(response: PlaylistResponse) {
        require(response.id.isNotBlank()) { "Playlist ID cannot be blank" }
        require(response.name.isNotBlank()) { "Playlist name cannot be blank" }
        require(response.tracks.total >= 0) { "Invalid track count: ${response.tracks.total}" }

        if (response.tracks.total == 0) {
            throw IllegalArgumentException("Playlist is empty")
        }
    }

    private fun getErrorMessage(exception: Throwable): String {
        return when (exception) {
            is HttpException -> {
                when (exception.code()) {
                    401 -> "Authentication failed. Please log in again."
                    403 -> "Access denied. Check your Spotify Premium subscription."
                    404 -> "Playlist not found or is private."
                    429 -> "Rate limit exceeded. Please try again later."
                    in 500..599 -> "Spotify server error. Please try again later."
                    else -> "Network error: ${exception.message()}"
                }
            }
            is SocketTimeoutException -> "Connection timeout. Check your internet connection."
            is IOException -> "Network error. Check your internet connection."
            is IllegalArgumentException -> exception.message ?: "Invalid data received"
            else -> exception.message ?: "An unexpected error occurred"
        }
    }
}

interface SpotifyApi {
    @GET("playlists/{playlist_id}")
    suspend fun getPlaylist(
        @Header("Authorization") auth: String,
        @Path("playlist_id") playlistId: String
    ): PlaylistResponse
}

// Enhanced data classes with validation
data class PlaylistResponse(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: String,
    @SerializedName("tracks") val tracks: TracksObject
) {
    init {
        require(id.isNotBlank()) { "Playlist ID cannot be blank" }
        require(name.isNotBlank()) { "Playlist name cannot be blank" }
    }
}

data class TracksObject(
    @SerializedName("total") val total: Int
) {
    init {
        require(total >= 0) { "Track count cannot be negative" }
    }
}
