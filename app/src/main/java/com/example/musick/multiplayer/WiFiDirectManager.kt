package com.example.musick.multiplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException

/**
 * Manages Wi-Fi Direct connections for multiplayer gameplay
 */
class WiFiDirectManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WiFiDirectManager"
        private const val SERVICE_INSTANCE = "_musick"
        private const val SERVICE_REG_TYPE = "_presence._tcp"
        const val SERVER_PORT = 8888
    }
    
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WiFiDirectBroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // State flows
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    
    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()
    
    // Network components
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var networkManager: MultiplayerNetworkManager? = null
    
    // Callbacks
    var onDeviceConnected: ((String) -> Unit)? = null
    var onDeviceDisconnected: ((String) -> Unit)? = null
    var onMessageReceived: ((MultiplayerMessage) -> Unit)? = null
    
    fun initialize(): Boolean {
        return try {
            if (wifiP2pManager == null) {
                Log.e(TAG, "Wi-Fi P2P not supported")
                return false
            }
            
            channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
            setupBroadcastReceiver()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct", e)
            false
        }
    }
    
    private fun setupBroadcastReceiver() {
        receiver = WiFiDirectBroadcastReceiver(this)
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        context.registerReceiver(receiver, intentFilter)
    }
    
    @SuppressLint("MissingPermission")
    fun startHostMode(hostName: String): Boolean {
        return try {
            if (!hasRequiredPermissions()) {
                Log.e(TAG, "Missing required permissions")
                return false
            }
            
            _isHost.value = true
            _connectionStatus.value = ConnectionStatus.CONNECTING
            
            // Start server socket for incoming connections
            scope.launch(Dispatchers.IO) {
                startServer()
            }
            
            // Make device discoverable
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group created successfully")
                    _connectionStatus.value = ConnectionStatus.HOST
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create group: $reason")
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            })
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start host mode", e)
            _connectionStatus.value = ConnectionStatus.ERROR
            false
        }
    }
    
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        return try {
            if (!hasRequiredPermissions()) {
                Log.e(TAG, "Missing required permissions for discovery")
                return false
            }
            
            _connectionStatus.value = ConnectionStatus.CONNECTING
            
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery started successfully")
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Discovery failed: $reason")
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            })
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _connectionStatus.value = ConnectionStatus.ERROR
            false
        }
    }
    
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: DiscoveredDevice): Boolean {
        return try {
            if (!hasRequiredPermissions()) {
                Log.e(TAG, "Missing required permissions for connection")
                return false
            }
            
            val config = WifiP2pConfig().apply {
                deviceAddress = device.address
            }
            
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated successfully")
                    _connectionStatus.value = ConnectionStatus.CONNECTING
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Connection failed: $reason")
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            })
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device", e)
            _connectionStatus.value = ConnectionStatus.ERROR
            false
        }
    }
    
    private suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(SERVER_PORT)
            Log.d(TAG, "Server started on port $SERVER_PORT")
            
            while (!serverSocket!!.isClosed) {
                try {
                    val client = serverSocket!!.accept()
                    Log.d(TAG, "Client connected: ${client.remoteSocketAddress}")
                    
                    // Handle client in separate coroutine
                    scope.launch(Dispatchers.IO) {
                        handleClientConnection(client)
                    }
                } catch (e: IOException) {
                    if (!serverSocket!!.isClosed) {
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
            withContext(Dispatchers.Main) {
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }
    
    private suspend fun handleClientConnection(client: Socket) {
        try {
            networkManager = MultiplayerNetworkManager(client) { message ->
                onMessageReceived?.invoke(message)
            }
            
            withContext(Dispatchers.Main) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                onDeviceConnected?.invoke(client.remoteSocketAddress.toString())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
            client.close()
        }
    }
    
    fun sendMessage(message: MultiplayerMessage) {
        networkManager?.sendMessage(message)
    }
    
    fun disconnect() {
        try {
            scope.launch(Dispatchers.IO) {
                serverSocket?.close()
                clientSocket?.close()
                networkManager?.disconnect()
            }
            
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removed successfully")
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove group: $reason")
                }
            })
            
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _isHost.value = false
            _discoveredDevices.value = emptyList()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
    
    fun cleanup() {
        disconnect()
        
        try {
            receiver?.let {
                context.unregisterReceiver(it)
            }
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    internal fun onPeersChanged() {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing permissions for peer discovery")
            return
        }
        
        wifiP2pManager?.requestPeers(channel) { peers ->
            val devices = peers.deviceList.map { device ->
                DiscoveredDevice(
                    name = device.deviceName ?: "Unknown Device",
                    address = device.deviceAddress,
                    isHost = device.isGroupOwner
                )
            }
            
            _discoveredDevices.value = devices
            Log.d(TAG, "Found ${devices.size} peers")
        }
    }
    
    internal fun onConnectionChanged(networkInfo: android.net.NetworkInfo?) {
        if (networkInfo?.isConnected == true) {
            // Connected to a group
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info.groupFormed && info.isGroupOwner) {
                    // This device is the group owner (host)
                    Log.d(TAG, "Device is group owner")
                    _isHost.value = true
                    _connectionStatus.value = ConnectionStatus.HOST
                } else if (info.groupFormed) {
                    // This device is a client
                    Log.d(TAG, "Device is client, connecting to host")
                    _isHost.value = false
                    
                    scope.launch(Dispatchers.IO) {
                        connectToHost(info.groupOwnerAddress.hostAddress ?: "")
                    }
                }
            }
        } else {
            // Disconnected
            Log.d(TAG, "Wi-Fi Direct disconnected")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }
    
    private suspend fun connectToHost(hostAddress: String) = withContext(Dispatchers.IO) {
        try {
            clientSocket = Socket(hostAddress, SERVER_PORT)
            
            networkManager = MultiplayerNetworkManager(clientSocket!!) { message ->
                onMessageReceived?.invoke(message)
            }
            
            withContext(Dispatchers.Main) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                onDeviceConnected?.invoke(hostAddress)
            }
            
            Log.d(TAG, "Connected to host at $hostAddress")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to host", e)
            withContext(Dispatchers.Main) {
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }
    
    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        
        return permissions.all { permission ->
            ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private class WiFiDirectBroadcastReceiver(private val manager: WiFiDirectManager) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(TAG, "Wi-Fi P2P state changed: $state")
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers changed")
                    manager.onPeersChanged()
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    Log.d(TAG, "Connection changed: ${networkInfo?.isConnected}")
                    manager.onConnectionChanged(networkInfo)
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "This device changed")
                }
            }
        }
    }
}