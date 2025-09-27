package com.example.musick.multiplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.musick.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for displaying connected players in multiplayer setup
 */
class ConnectedPlayersAdapter(
    private val players: List<MultiplayerPlayer>
) : RecyclerView.Adapter<ConnectedPlayersAdapter.PlayerViewHolder>() {
    
    class PlayerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playerCard: MaterialCardView = view.findViewById(R.id.playerCard)
        val playerNameText: TextView = view.findViewById(R.id.playerNameText)
        val hostIndicator: TextView = view.findViewById(R.id.hostIndicator)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connected_player, parent, false)
        return PlayerViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = players[position]
        
        holder.playerNameText.text = player.name
        
        if (player.isHost) {
            holder.hostIndicator.visibility = View.VISIBLE
            holder.hostIndicator.text = "HOST"
            holder.playerCard.strokeColor = holder.itemView.context.getColor(R.color.spotify_green)
        } else {
            holder.hostIndicator.visibility = View.GONE
            holder.playerCard.strokeColor = holder.itemView.context.getColor(R.color.surface_variant)
        }
    }
    
    override fun getItemCount() = players.size
}

/**
 * Adapter for displaying discovered devices that can be joined
 */
class DiscoveredDevicesAdapter(
    private val devices: List<DiscoveredDevice>,
    private val onDeviceClick: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DiscoveredDevicesAdapter.DeviceViewHolder>() {
    
    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceCard: MaterialCardView = view.findViewById(R.id.deviceCard)
        val deviceNameText: TextView = view.findViewById(R.id.deviceNameText)
        val deviceStatusText: TextView = view.findViewById(R.id.deviceStatusText)
        val joinButton: MaterialButton = view.findViewById(R.id.joinButton)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discovered_device, parent, false)
        return DeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        holder.deviceNameText.text = device.name
        
        if (device.isHost) {
            holder.deviceStatusText.text = "Hosting Musick game"
            holder.deviceStatusText.visibility = View.VISIBLE
            holder.joinButton.isEnabled = true
            holder.deviceCard.strokeColor = holder.itemView.context.getColor(R.color.spotify_green)
        } else {
            holder.deviceStatusText.text = "Available"
            holder.deviceStatusText.visibility = View.VISIBLE
            holder.joinButton.isEnabled = true
            holder.deviceCard.strokeColor = holder.itemView.context.getColor(R.color.surface_variant)
        }
        
        holder.joinButton.setOnClickListener {
            onDeviceClick(device)
        }
        
        holder.deviceCard.setOnClickListener {
            onDeviceClick(device)
        }
    }
    
    override fun getItemCount() = devices.size
}

/**
 * Adapter for displaying multiplayer scores in the game
 */
class MultiplayerScoreAdapter(
    private val players: List<MultiplayerPlayer>,
    private val onScoreChange: ((String, Int) -> Unit)? = null,
    private val isHost: Boolean = false
) : RecyclerView.Adapter<MultiplayerScoreAdapter.ScoreViewHolder>() {
    
    class ScoreViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playerNameText: TextView = view.findViewById(R.id.playerNameText)
        val scoreText: TextView = view.findViewById(R.id.scoreText)
        val minusButton: MaterialButton? = view.findViewById(R.id.minusOneButton)
        val plusButton: MaterialButton? = view.findViewById(R.id.plusOneButton)
        val hostIndicator: TextView? = view.findViewById(R.id.hostIndicator)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScoreViewHolder {
        val layoutRes = if (isHost) {
            R.layout.item_multiplayer_score_host // With +/- buttons
        } else {
            R.layout.item_multiplayer_score_client // Without buttons
        }
        
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ScoreViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ScoreViewHolder, position: Int) {
        val player = players[position]
        
        holder.playerNameText.text = player.name
        holder.scoreText.text = player.score.toString()
        
        // Highlight player with highest score
        val maxScore = players.maxOfOrNull { it.score } ?: 0
        if (player.score == maxScore && player.score > 0) {
            holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
            holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.spotify_green))
        } else {
            holder.scoreText.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            holder.playerNameText.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
        }
        
        // Show host indicator
        holder.hostIndicator?.let { indicator ->
            if (player.isHost) {
                indicator.visibility = View.VISIBLE
                indicator.text = "HOST"
            } else {
                indicator.visibility = View.GONE
            }
        }
        
        // Setup score change buttons (only for host)
        if (isHost && onScoreChange != null) {
            holder.minusButton?.setOnClickListener {
                onScoreChange.invoke(player.id, -1)
            }
            
            holder.plusButton?.setOnClickListener {
                onScoreChange.invoke(player.id, 1)
            }
        }
    }
    
    override fun getItemCount() = players.size
}