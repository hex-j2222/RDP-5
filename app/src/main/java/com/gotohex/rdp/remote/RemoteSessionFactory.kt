package com.gotohex.rdp.remote

import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpCredentials
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.data.model.SshAuthType
import com.gotohex.rdp.rdp.protocol.RdpRemoteAdapter
import com.gotohex.rdp.ssh.protocol.SshAuthMode
import com.gotohex.rdp.ssh.protocol.SshClient
import com.gotohex.rdp.ssh.protocol.SshCredentials
import com.gotohex.rdp.vnc.protocol.VncClient
import com.gotohex.rdp.vnc.protocol.VncCredentials

/**
 * Builds the right [RemoteSessionClient] implementation for a profile's
 * [ProtocolType]. This is the single place that knows about all three
 * protocol client classes — everything downstream (the session ViewModel,
 * the session UI) only depends on the common [RemoteSessionClient] surface.
 */
object RemoteSessionFactory {

    fun create(
        profile: RdpProfile,
        displayWidth: Int,
        displayHeight: Int,
    ): RemoteSessionClient = when (profile.protocolType) {
        ProtocolType.RDP -> RdpRemoteAdapter(
            credentials = RdpCredentials(
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = profile.password,
                domain = profile.domain,
                useNla = profile.useNla,
                gatewayEnabled = profile.gatewayEnabled,
                gatewayHost = profile.gatewayHost,
                gatewayPort = profile.gatewayPort,
                gatewayUsername = profile.gatewayUsername,
                gatewayPassword = profile.gatewayPassword,
                gatewayDomain = profile.gatewayDomain,
            ),
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            performanceMode = profile.performanceFlags,
        )

        ProtocolType.VNC -> VncClient(
            credentials = VncCredentials(
                host = profile.host,
                port = profile.port,
                password = profile.password,
                viewOnly = profile.vncViewOnly,
            )
        )

        ProtocolType.SSH -> SshClient(
            credentials = SshCredentials(
                host = profile.host,
                port = profile.port,
                username = profile.username,
                authMode = when (profile.sshAuthType) {
                    SshAuthType.PASSWORD -> SshAuthMode.PASSWORD
                    SshAuthType.PRIVATE_KEY -> SshAuthMode.PRIVATE_KEY
                },
                password = profile.password,
                privateKeyPem = profile.sshPrivateKey,
                privateKeyPassphrase = profile.sshPrivateKeyPassphrase,
            )
        )
    }
}
