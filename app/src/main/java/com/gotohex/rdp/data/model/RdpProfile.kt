package com.gotohex.rdp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * The remote protocol a profile connects with. Determines which fields in
 * [RdpProfile] are relevant and which client implementation handles the
 * session (see com.gotohex.rdp.remote.RemoteSessionClient).
 */
enum class ProtocolType(val defaultPort: Int, val label: String) {
    RDP(3389, "RDP"),
    VNC(5900, "VNC"),
    SSH(22, "SSH");

    companion object {
        fun fromName(name: String): ProtocolType =
            entries.firstOrNull { it.name == name } ?: RDP
    }
}

/** SSH authentication method. */
enum class SshAuthType { PASSWORD, PRIVATE_KEY }

/**
 * RDP Connection Profile stored in local database.
 * Supports multiple simultaneous sessions, and — despite the historical name
 * kept for migration simplicity — now supports RDP, VNC, and SSH connections
 * via [protocolType].
 */
@Entity(tableName = "rdp_profiles")
data class RdpProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,                          // Custom display name e.g. "Work Server"
    val protocolType: ProtocolType = ProtocolType.RDP,
    val host: String,                          // IP or hostname
    val port: Int = 3389,                      // Default RDP port
    val username: String,
    val password: String,                      // Stored encrypted

    // ── RDP-specific ───────────────────────────────────────────────────────
    val domain: String = "",                   // Windows domain
    val width: Int = 0,                        // 0 = auto detect
    val height: Int = 0,                       // 0 = auto detect
    val colorDepth: Int = 32,                  // 16, 24, or 32 bit
    val enableSound: Boolean = false,
    val enableClipboard: Boolean = true,
    val enableDriveRedirect: Boolean = false,
    val useNla: Boolean = true,                // NLA authentication
    val performanceFlags: Int = RdpPerformance.LAN,

    // ── RD Gateway (RDP only) ──────────────────────────────────────────────
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",

    // ── VNC-specific ────────────────────────────────────────────────────────
    // VNC classically authenticates with a session password only (no
    // username). `password` above is reused as that VNC password.
    val vncViewOnly: Boolean = false,

    // ── SSH-specific ────────────────────────────────────────────────────────
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",            // PEM contents, if PRIVATE_KEY
    val sshPrivateKeyPassphrase: String = "",

    val lastScreenshotPath: String? = null,    // Path to last screenshot bitmap
    val lastConnected: Long = 0L,
    val isConnected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

object RdpPerformance {
    const val LOW_BANDWIDTH = 0    // 2G / very slow
    const val MEDIUM = 1           // 3G
    const val WIFI = 2             // WiFi
    const val LAN = 3              // LAN / Fast
    const val AUTO = 4             // Auto-detect and adapt
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class RdpCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String,
    val useNla: Boolean = true,
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",
)

data class RdpSessionInfo(
    val profileId: String,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val bandwidthKbps: Int = 0
)
