package com.gotohex.rdp.ssh.protocol

import android.util.Log
import com.gotohex.rdp.remote.*
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class SshAuthMode { PASSWORD, PRIVATE_KEY }

data class SshCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode,
    val password: String = "",
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = "",
)

/**
 * SSH client backed by JSch, exposing an interactive shell (PTY) channel
 * through the same [RemoteSessionClient] surface used by RDP/VNC.
 *
 * Unlike RDP/VNC this is a *terminal*, not a framebuffer — [frameUpdates] is
 * never emitted; instead raw terminal bytes are surfaced via
 * [terminalOutput], and [sendText] (rather than scan-code key events) is the
 * primary input path, matching how [com.gotohex.rdp.ui.screens.terminal.TerminalScreen]
 * drives it.
 */
class SshClient(
    private val credentials: SshCredentials,
    private val termCols: Int = 100,
    private val termRows: Int = 32,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "SshClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var channelOut: OutputStream? = null
    private var channelIn: InputStream? = null
    @Volatile private var connected = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val jsch = JSch()
            if (credentials.authMode == SshAuthMode.PRIVATE_KEY && credentials.privateKeyPem.isNotBlank()) {
                jsch.addIdentity(
                    "hexrdp-key",
                    credentials.privateKeyPem.toByteArray(Charsets.UTF_8),
                    null,
                    credentials.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                )
            }

            val sess = jsch.getSession(credentials.username, credentials.host, credentials.port)
            if (credentials.authMode == SshAuthMode.PASSWORD) {
                sess.setPassword(credentials.password)
            }
            // Mobile-friendly default: don't hard-fail on unknown host keys
            // (no local known_hosts UI yet). Equivalent to ssh -o StrictHostKeyChecking=no.
            sess.setConfig("StrictHostKeyChecking", "no")
            sess.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            sess.timeout = CONNECT_TIMEOUT_MS
            session = sess

            val connectStart = System.currentTimeMillis()
            sess.connect(CONNECT_TIMEOUT_MS)
            latencyMs = System.currentTimeMillis() - connectStart

            val ch = sess.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", termCols, termRows, 0, 0)
            ch.setPty(true)
            channelIn = ch.inputStream
            channelOut = ch.outputStream
            ch.connect(CONNECT_TIMEOUT_MS)
            channel = ch

            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)
            ioScope.launch { readLoop() }

            true
        } catch (e: Exception) {
            Log.e(TAG, "SSH connect failed", e)
            val authFailure = e.message?.contains("Auth", ignoreCase = true) == true ||
                e.message?.contains("authentication", ignoreCase = true) == true
            _error.emit(e.message ?: "SSH connection failed")
            _sessionState.emit(if (authFailure) RemoteSessionState.AUTH_FAILED else RemoteSessionState.ERROR)
            cleanup()
            false
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        val pending = ByteArrayOutputStream()
        try {
            val stream = channelIn ?: return
            while (connected) {
                val n = stream.read(buffer)
                if (n < 0) break
                pending.reset()
                pending.write(buffer, 0, n)
                _terminalOutput.emit(TerminalOutput(pending.toString("UTF-8")))
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "SSH read loop error", e)
                _error.emit(e.message ?: "Connection lost")
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resize PTY", e)
        }
    }

    // ── Input — terminal sessions take raw text, not framebuffer events ────

    override fun sendText(text: String) {
        try {
            channelOut?.write(text.toByteArray(Charsets.UTF_8))
            channelOut?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send terminal input", e)
        }
    }

    /** Sends a single raw control byte, e.g. Ctrl+C = 0x03. */
    fun sendControlByte(byte: Int) {
        try {
            channelOut?.write(byte)
            channelOut?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send control byte", e)
        }
    }

    override fun sendCtrlAltDel() { /* not meaningful over a terminal */ }
    override fun sendMouseMove(x: Int, y: Int) { /* terminal sessions don't use pointer input */ }
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) { }
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) { }
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        // Only forward key-down for control keys relevant in a terminal —
        // see TerminalScreen's extra-keys row, which calls sendControlByte /
        // sendText directly for the keys it cares about. Scan-code based
        // input (hardware keyboard via the shared ExtraKeysBar) is mapped to
        // ANSI escape sequences here for the navigation/function keys.
        if (!down) return
        val seq = SshKeyMap.scanCodeToAnsiSequence(scanCode, extended) ?: return
        sendText(seq)
    }

    override fun disconnect() {
        connected = false
        ioScope.cancel()
        cleanup()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private fun cleanup() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null; channelIn = null; channelOut = null
    }
}
