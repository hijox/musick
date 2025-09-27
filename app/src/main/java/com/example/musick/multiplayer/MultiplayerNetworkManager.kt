package com.example.musick.multiplayer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Handles network communication between devices in multiplayer mode
 */
class MultiplayerNetworkManager(
    private val socket: Socket,
    private val onMessageReceived: (MultiplayerMessage) -> Unit
) {
    companion object {
        private const val TAG = "MultiplayerNetwork"
        private const val MESSAGE_DELIMITER = "\n"
    }
    
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var inputStream: BufferedReader? = null
    private var outputStream: PrintWriter? = null
    private var isRunning = true
    
    init {
        initializeStreams()
        startListening()
    }
    
    private fun initializeStreams() {
        try {
            inputStream = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            )
            outputStream = PrintWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true // Auto-flush
            )
            Log.d(TAG, "Streams initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize streams", e)
            disconnect()
        }
    }
    
    private fun startListening() {
        scope.launch {
            try {
                while (isRunning && !socket.isClosed) {
                    try {
                        val line = inputStream?.readLine()
                        if (line != null) {
                            handleReceivedMessage(line)
                        } else {
                            // Stream closed
                            Log.d(TAG, "Input stream closed")
                            break
                        }
                    } catch (e: SocketException) {
                        if (isRunning) {
                            Log.e(TAG, "Socket exception while reading", e)
                        }
                        break
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "IOException while reading", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in message listener", e)
            } finally {
                Log.d(TAG, "Message listening stopped")
                disconnect()
            }
        }
    }
    
    private fun handleReceivedMessage(messageJson: String) {
        try {
            Log.d(TAG, "Received message: $messageJson")
            val message = deserializeMessage(messageJson)
            if (message != null) {
                onMessageReceived(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received message", e)
        }
    }
    
    fun sendMessage(message: MultiplayerMessage) {
        scope.launch {
            try {
                val messageJson = serializeMessage(message)
                if (messageJson != null) {
                    outputStream?.println(messageJson)
                    outputStream?.flush()
                    Log.d(TAG, "Sent message: $messageJson")
                } else {
                    Log.e(TAG, "Failed to serialize message: $message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                disconnect()
            }
        }
    }
    
    private fun serializeMessage(message: MultiplayerMessage): String? {
        return try {
            val wrapper = MessageWrapper(
                type = message.javaClass.simpleName,
                data = gson.toJsonTree(message)
            )
            gson.toJson(wrapper)
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing message", e)
            null
        }
    }
    
    private fun deserializeMessage(json: String): MultiplayerMessage? {
        return try {
            val wrapper = gson.fromJson(json, MessageWrapper::class.java)
            
            when (wrapper.type) {
                "PlayerJoined" -> gson.fromJson(wrapper.data, MultiplayerMessage.PlayerJoined::class.java)
                "PlayerLeft" -> gson.fromJson(wrapper.data, MultiplayerMessage.PlayerLeft::class.java)
                "BuzzerPressed" -> gson.fromJson(wrapper.data, MultiplayerMessage.BuzzerPressed::class.java)
                "GameStateUpdate" -> gson.fromJson(wrapper.data, MultiplayerMessage.GameStateUpdate::class.java)
                "ScoreUpdate" -> gson.fromJson(wrapper.data, MultiplayerMessage.ScoreUpdate::class.java)
                "SongRevealed" -> gson.fromJson(wrapper.data, MultiplayerMessage.SongRevealed::class.java)
                "NextSong" -> gson.fromJson(wrapper.data, MultiplayerMessage.NextSong::class.java)
                "GameStarted" -> gson.fromJson(wrapper.data, MultiplayerMessage.GameStarted::class.java)
                else -> {
                    Log.w(TAG, "Unknown message type: ${wrapper.type}")
                    null
                }
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Error deserializing message: $json", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error deserializing message", e)
            null
        }
    }
    
    fun disconnect() {
        isRunning = false
        
        scope.launch {
            try {
                inputStream?.close()
                outputStream?.close()
                socket.close()
                Log.d(TAG, "Network connection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing network connection", e)
            }
        }
        
        scope.cancel()
    }
    
    fun isConnected(): Boolean {
        return !socket.isClosed && socket.isConnected && isRunning
    }
    
    /**
     * Wrapper class for message serialization
     */
    private data class MessageWrapper(
        val type: String,
        val data: com.google.gson.JsonElement
    )
}