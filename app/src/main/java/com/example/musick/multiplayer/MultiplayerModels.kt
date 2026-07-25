package com.example.musick.multiplayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a player in the multiplayer game
 */
@Parcelize
data class MultiplayerPlayer(
    val id: String,
    val name: String,
    val deviceAddress: String,
    var score: Int = 0,
    var isHost: Boolean = false
) : Parcelable

/**
 * Game state that gets synchronized across all devices
 */
@Parcelize
data class GameState(
    val currentSong: String? = null,
    val currentArtist: String? = null,
    val albumArtUrl: String? = null,
    val isRevealed: Boolean = false,
    val isPaused: Boolean = false,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val players: List<MultiplayerPlayer> = emptyList(),
    val gameStarted: Boolean = false,
    // New: playlist progress tracking
    val totalTracks: Int = 0,
    val seenTrackUris: Set<String> = emptySet()
) : Parcelable

/**
 * Messages sent between devices
 */
sealed class MultiplayerMessage {
    abstract val timestamp: Long
    abstract val senderId: String

    data class PlayerJoined(
        override val timestamp: Long,
        override val senderId: String,
        val playerName: String
    ) : MultiplayerMessage()

    data class PlayerLeft(
        override val timestamp: Long,
        override val senderId: String
    ) : MultiplayerMessage()

    data class BuzzerPressed(
        override val timestamp: Long,
        override val senderId: String
    ) : MultiplayerMessage()

    data class GameStateUpdate(
        override val timestamp: Long,
        override val senderId: String,
        val gameState: GameState
    ) : MultiplayerMessage()

    data class ScoreUpdate(
        override val timestamp: Long,
        override val senderId: String,
        val playerId: String,
        val newScore: Int
    ) : MultiplayerMessage()

    data class SongRevealed(
        override val timestamp: Long,
        override val senderId: String,
        val songName: String,
        val artistName: String
    ) : MultiplayerMessage()

    data class NextSong(
        override val timestamp: Long,
        override val senderId: String
    ) : MultiplayerMessage()

    data class GameStarted(
        override val timestamp: Long,
        override val senderId: String,
        val playlistId: String
    ) : MultiplayerMessage()

    data class RoundTimeout(
        override val timestamp: Long,
        override val senderId: String
    ) : MultiplayerMessage()
}

/**
 * Connection status for devices
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    HOST,
    ERROR
}

/**
 * Device information for discovery
 */
@Parcelize
data class DiscoveredDevice(
    val name: String,
    val address: String,
    val isHost: Boolean = false
) : Parcelable