package com.example.musick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musick.multiplayer.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

class MultiplayerSetupActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MultiplayerSetup"
    }
    
    // UI Components
    private lateinit var playerNameInput: TextInputEditText
    private lateinit var hostGameButton: MaterialButton
    private lateinit var joinGameButton: MaterialButton
    private lateinit var startGameButton: MaterialButton
    private lateinit var backButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var statusText: MaterialTextView
    private lateinit var playersCard: MaterialCardView
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var devicesCard: MaterialCardView
    private lateinit var devicesRecyclerView: RecyclerView
    
    // Multiplayer components
    private lateinit var wifiDirectManager: WiFiDirectManager
    private var gameMode: GameMode = GameMode.NONE
    private var playlistId: String? = null
    
    // State
    private var connectedPlayers = mutableListOf<MultiplayerPlayer>()
    private var discoveredDevices = mutableListOf<DiscoveredDevice>()
    private var currentPlayer: MultiplayerPlayer? = null
    
    // Adapters
    private lateinit var playersAdapter: ConnectedPlayersAdapter
    private lateinit var devicesAdapter: DiscoveredDevicesAdapter
    
    enum class GameMode {
        NONE, HOST, JOIN
    }
    
    // Permission request launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
        } else {
            Log.e(TAG, "Some permissions denied")
            Toast.makeText(this, "Permissions required for multiplayer mode", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiplayer_setup)
        
        playlistId = intent.getStringExtra("PLAYLIST_ID")
        
        initializeViews()
        setupWiFiDirect()
        checkPermissions()
        setupListeners()
        setupRecyclerViews()
    }
    
    private fun initializeViews() {
        playerNameInput = findViewById(R.id.playerNameInput)
        hostGameButton = findViewById(R.id.hostGameButton)
        joinGameButton = findViewById(R.id.joinGameButton)
        startGameButton = findViewById(R.id.startGameButton)
        backButton = findViewById(R.id.backButton)
        statusText = findViewById(R.id.statusText)
        playersCard = findViewById(R.id.playersCard)
        playersRecyclerView = findViewById(R.id.playersRecyclerView)
        devicesCard = findViewById(R.id.devicesCard)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        
        // Initially hide cards
        playersCard.visibility = View.GONE
        devicesCard.visibility = View.GONE
        startGameButton.visibility = View.GONE
    }
    
    private fun setupWiFiDirect() {
        wifiDirectManager = WiFiDirectManager(this).apply {
            onDeviceConnected = { address ->
                runOnUiThread {
                    statusText.text = "Connected to device: $address"
                    updateUI()
                }
            }
            
            onDeviceDisconnected = { address ->
                runOnUiThread {
                    statusText.text = "Disconnected from: $address"
                    // Remove player from list
                    connectedPlayers.removeAll { it.deviceAddress == address }
                    playersAdapter.notifyDataSetChanged()
                    updateUI()
                }
            }
            
            onMessageReceived = { message ->
                handleMultiplayerMessage(message)
            }
        }
        
        if (!wifiDirectManager.initialize()) {
            Toast.makeText(this, "Wi-Fi Direct not supported on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        // Observe state changes
        lifecycleScope.launch {
            wifiDirectManager.connectionStatus.collect { status ->
                updateConnectionStatus(status)
            }
        }
        
        lifecycleScope.launch {
            wifiDirectManager.discoveredDevices.collect { devices ->
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)
                devicesAdapter.notifyDataSetChanged()
            }
        }
    }
    
    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }
        
        hostGameButton.setOnClickListener {
            val playerName = playerNameInput.text?.toString()?.trim()
            if (playerName.isNullOrEmpty()) {
                playerNameInput.error = "Please enter your name"
                return@setOnClickListener
            }
            
            startHostMode(playerName)
        }
        
        joinGameButton.setOnClickListener {
            val playerName = playerNameInput.text?.toString()?.trim()
            if (playerName.isNullOrEmpty()) {
                playerNameInput.error = "Please enter your name"
                return@setOnClickListener
            }
            
            startJoinMode(playerName)
        }
        
        startGameButton.setOnClickListener {
            if (gameMode == GameMode.HOST && connectedPlayers.size >= 2) {
                startMultiplayerGame()
            } else {
                Toast.makeText(this, "Need at least 2 players to start", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupRecyclerViews() {
        // Connected players
        playersAdapter = ConnectedPlayersAdapter(connectedPlayers)
        playersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MultiplayerSetupActivity)
            adapter = playersAdapter
        }
        
        // Discovered devices
        devicesAdapter = DiscoveredDevicesAdapter(discoveredDevices) { device ->
            connectToDevice(device)
        }
        devicesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MultiplayerSetupActivity)
            adapter = devicesAdapter
        }
    }
    
    private fun startHostMode(playerName: String) {
        gameMode = GameMode.HOST
        
        // Create host player
        currentPlayer = MultiplayerPlayer(
            id = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID),
            name = playerName,
            deviceAddress = "host",
            isHost = true
        )
        
        // Add host to players list
        connectedPlayers.clear()
        connectedPlayers.add(currentPlayer!!)
        playersAdapter.notifyDataSetChanged()
        
        statusText.text = "Starting host mode..."
        
        if (wifiDirectManager.startHostMode(playerName)) {
            hostGameButton.isEnabled = false
            joinGameButton.isEnabled = false
            updateUI()
        } else {
            Toast.makeText(this, "Failed to start host mode", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startJoinMode(playerName: String) {
        gameMode = GameMode.JOIN
        
        // Create client player
        currentPlayer = MultiplayerPlayer(
            id = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID),
            name = playerName,
            deviceAddress = "client",
            isHost = false
        )
        
        statusText.text = "Looking for games..."
        
        if (wifiDirectManager.startDiscovery()) {
            hostGameButton.isEnabled = false
            joinGameButton.isEnabled = false
            devicesCard.visibility = View.VISIBLE
            updateUI()
        } else {
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun connectToDevice(device: DiscoveredDevice) {
        statusText.text = "Connecting to ${device.name}..."
        
        if (!wifiDirectManager.connectToDevice(device)) {
            Toast.makeText(this, "Failed to connect to ${device.name}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateConnectionStatus(status: ConnectionStatus) {
        runOnUiThread {
            when (status) {
                ConnectionStatus.DISCONNECTED -> {
                    statusText.text = "Disconnected"
                }
                ConnectionStatus.CONNECTING -> {
                    statusText.text = "Connecting..."
                }
                ConnectionStatus.CONNECTED -> {
                    if (gameMode == GameMode.JOIN) {
                        statusText.text = "Connected! Joining game..."
                        // Send join message
                        currentPlayer?.let { player ->
                            val joinMessage = MultiplayerMessage.PlayerJoined(
                                timestamp = System.currentTimeMillis(),
                                senderId = player.id,
                                playerName = player.name
                            )
                            wifiDirectManager.sendMessage(joinMessage)
                        }
                    }
                }
                ConnectionStatus.HOST -> {
                    statusText.text = "Hosting game - waiting for players..."
                    playersCard.visibility = View.VISIBLE
                    startGameButton.visibility = View.VISIBLE
                }
                ConnectionStatus.ERROR -> {
                    statusText.text = "Connection error occurred"
                    Toast.makeText(this, "Connection error", Toast.LENGTH_SHORT).show()
                }
            }
            updateUI()
        }
    }
    
    private fun handleMultiplayerMessage(message: MultiplayerMessage) {
        runOnUiThread {
            when (message) {
                is MultiplayerMessage.PlayerJoined -> {
                    if (gameMode == GameMode.HOST) {
                        // Add new player to list
                        val newPlayer = MultiplayerPlayer(
                            id = message.senderId,
                            name = message.playerName,
                            deviceAddress = message.senderId
                        )
                        
                        if (!connectedPlayers.any { it.id == newPlayer.id }) {
                            connectedPlayers.add(newPlayer)
                            playersAdapter.notifyDataSetChanged()
                            
                            statusText.text = "${message.playerName} joined the game"
                            
                            // Send current game state to new player
                            val gameState = GameState(players = connectedPlayers)
                            val stateMessage = MultiplayerMessage.GameStateUpdate(
                                timestamp = System.currentTimeMillis(),
                                senderId = currentPlayer?.id ?: "",
                                gameState = gameState
                            )
                            wifiDirectManager.sendMessage(stateMessage)
                        }
                    }
                }
                
                is MultiplayerMessage.GameStateUpdate -> {
                    if (gameMode == GameMode.JOIN) {
                        // Update players list with game state
                        connectedPlayers.clear()
                        connectedPlayers.addAll(message.gameState.players)
                        
                        // Add self if not in list
                        currentPlayer?.let { self ->
                            if (!connectedPlayers.any { it.id == self.id }) {
                                connectedPlayers.add(self)
                            }
                        }
                        
                        playersAdapter.notifyDataSetChanged()
                        playersCard.visibility = View.VISIBLE
                        statusText.text = "Joined game with ${connectedPlayers.size} players"
                    }
                }
                
                is MultiplayerMessage.GameStarted -> {
                    if (gameMode == GameMode.JOIN) {
                        // Navigate to multiplayer game
                        startMultiplayerGame(message.playlistId)
                    }
                }
                
                else -> {
                    Log.d(TAG, "Unhandled message type: ${message.javaClass.simpleName}")
                }
            }
        }
    }
    
    private fun startMultiplayerGame(playlistIdOverride: String? = null) {
        val gamePlaylistId = playlistIdOverride ?: playlistId
        
        if (gamePlaylistId == null) {
            Toast.makeText(this, "No playlist selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        // If host, notify all clients game is starting
        if (gameMode == GameMode.HOST) {
            val startMessage = MultiplayerMessage.GameStarted(
                timestamp = System.currentTimeMillis(),
                senderId = currentPlayer?.id ?: "",
                playlistId = gamePlaylistId
            )
            wifiDirectManager.sendMessage(startMessage)
        }
        
        // Start multiplayer game activity
        val intent = Intent(this, MultiplayerGameActivity::class.java).apply {
            putExtra("PLAYLIST_ID", gamePlaylistId)
            putExtra("IS_HOST", gameMode == GameMode.HOST)
            putExtra("CURRENT_PLAYER", currentPlayer)
            putParcelableArrayListExtra("CONNECTED_PLAYERS", ArrayList(connectedPlayers))
        }
        
        startActivity(intent)
    }
    
    private fun updateUI() {
        // Update start game button visibility
        startGameButton.isEnabled = gameMode == GameMode.HOST && connectedPlayers.size >= 2
    }
    
    private fun checkPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        wifiDirectManager.cleanup()
    }
}