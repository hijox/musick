package com.example.musick

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

object SpotifyApiClient {
    private const val BASE_URL = "https://api.spotify.com/v1/"

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("Accept", "application/json")
                .method(original.method, original.body)
            chain.proceed(requestBuilder.build())
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    fun create(): SpotifyApi = retrofit.create(SpotifyApi::class.java)
}

interface SpotifyApi {
    @GET("playlists/{playlist_id}")
    suspend fun getPlaylist(
        @Header("Authorization") auth: String,
        @Path("playlist_id") playlistId: String
    ): PlaylistResponse
}

data class PlaylistResponse(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: String,
    @SerializedName("tracks") val tracks: TracksObject
)

data class TracksObject(
    @SerializedName("total") val total: Int
)