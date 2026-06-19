package com.gotohex.rdp.rdp.protocol

import com.gotohex.rdp.data.model.RdpCredentials
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.remote.*
import com.gotohex.rdp.rdp.native.AFreeRdpBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Adapts RDP connectivity to the common [RemoteSessionClient] surface used
 * by the session UI for all three protocols.
 *
 * Prefers the native **aFreeRDP** backend ([AFreeRdpBridge]) when its `.so`
 * has been built locally (see `app/src/main/cpp/SETUP.md`) — this gives
 * production-grade FreeRDP compatibility instead of a hand-rolled parser.
 * When the native library isn't present (e.g. you haven't built it yet),
 * this transparently falls back to the existing pure-Kotlin [RdpClient], so
 * the app always works out of the box.
 */
class RdpRemoteAdapter(
    private val credentials: RdpCredentials,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val performanceMode: Int = RdpPerformance.AUTO,
) : RemoteSessionClient {

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    /** True once [connect] has decided which backend is in use. */
    val usingNativeFreeRdp: Boolean get() = nativeBridge != null

    private var nativeBridge: AFreeRdpBridge? = null
    private var legacyClient: RdpClient? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(): Boolean {
        return if (AFreeRdpBridge.isAvailable) {
            connectNative()
        } else {
            connectLegacy()
        }
    }

    // ── Native aFreeRDP path ──────────────────────────────────────────────

    private suspend fun connectNative(): Boolean {
        val bridge = AFreeRdpBridge().also { nativeBridge = it }
        bridge.init()

        adapterScope.launch {
            bridge.frames.collect { f ->
                _frameUpdates.emit(RemoteFrameUpdate(f.x, f.y, f.width, f.height, f.pixels, f.fullScreen))
            }
        }
        adapterScope.launch {
            bridge.errors.collect { msg -> _error.emit(msg) }
        }

        _sessionState.value = RemoteSessionState.CONNECTING
        val ok = withContext(Dispatchers.IO) {
            bridge.connect(
                host = credentials.host,
                port = credentials.port,
                username = credentials.username,
                password = credentials.password,
                domain = credentials.domain,
                width = displayWidth,
                height = displayHeight,
                useNla = credentials.useNla,
                gatewayEnabled = credentials.gatewayEnabled,
                gatewayHost = credentials.gatewayHost,
                gatewayPort = credentials.gatewayPort,
                gatewayUsername = credentials.gatewayUsername,
                gatewayPassword = credentials.gatewayPassword,
                gatewayDomain = credentials.gatewayDomain,
            )
        }
        _sessionState.value = if (ok) RemoteSessionState.CONNECTED else RemoteSessionState.ERROR
        return ok
    }

    // ── Legacy pure-Kotlin path (existing hand-written RDP client) ──────────

    private suspend fun connectLegacy(): Boolean {
        val client = RdpClient(
            credentials = credentials,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            performanceMode = performanceMode,
        ).also { legacyClient = it }

        adapterScope.launch {
            client.sessionState.collect { s ->
                _sessionState.value = when (s) {
                    RdpSessionState.DISCONNECTED -> RemoteSessionState.DISCONNECTED
                    RdpSessionState.CONNECTING -> RemoteSessionState.CONNECTING
                    RdpSessionState.CONNECTED -> RemoteSessionState.CONNECTED
                    RdpSessionState.RECONNECTING -> RemoteSessionState.RECONNECTING
                    RdpSessionState.AUTH_FAILED -> RemoteSessionState.AUTH_FAILED
                    RdpSessionState.ERROR -> RemoteSessionState.ERROR
                }
            }
        }
        adapterScope.launch {
            client.error.collect { msg -> _error.emit(msg) }
        }
        adapterScope.launch {
            client.frameUpdates.collect { f ->
                _frameUpdates.emit(RemoteFrameUpdate(f.x, f.y, f.width, f.height, f.pixels, f.fullScreen))
                latencyMs = client.latencyMs
            }
        }

        return client.connect()
    }

    // ── Input (routed to whichever backend connected) ───────────────────────

    override fun sendMouseMove(x: Int, y: Int) {
        legacyClient?.sendMouseMove(x, y)
        nativeBridge?.sendMouse(x, y, MOUSE_FLAG_MOVE)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        legacyClient?.sendMouseClick(x, y, button.toLegacy(), down)
        nativeBridge?.let { bridge ->
            val flags = mouseClickFlags(button, down)
            bridge.sendMouse(x, y, flags)
        }
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        legacyClient?.sendMouseScroll(x, y, delta)
        nativeBridge?.sendMouse(x, y, if (delta > 0) MOUSE_FLAG_WHEEL_UP else MOUSE_FLAG_WHEEL_DOWN)
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        legacyClient?.sendKeyEvent(scanCode, down, extended)
        nativeBridge?.sendKey(scanCode, down, extended)
    }

    override fun sendCtrlAltDel() {
        legacyClient?.sendCtrlAltDel()
        nativeBridge?.let {
            sendKeyEvent(0x1D, true); sendKeyEvent(0x38, true)
            sendKeyEvent(0x53, true, extended = true)
            sendKeyEvent(0x53, false, extended = true)
            sendKeyEvent(0x38, false); sendKeyEvent(0x1D, false)
        }
    }

    override fun sendText(text: String) {
        // RDP has no terminal text channel; unicode key events would be the
        // equivalent, but no caller currently needs this for RDP sessions.
    }

    override fun disconnect() {
        legacyClient?.disconnect()
        nativeBridge?.let { it.disconnect(); it.free() }
        adapterScope.cancel()
    }

    private companion object {
        const val MOUSE_FLAG_MOVE = 0x0800
        const val MOUSE_FLAG_BUTTON1 = 0x1000 // left
        const val MOUSE_FLAG_BUTTON2 = 0x2000 // right
        const val MOUSE_FLAG_BUTTON3 = 0x4000 // middle
        const val MOUSE_FLAG_DOWN = 0x8000
        const val MOUSE_FLAG_WHEEL_UP = 0x0200 or 0x0100
        const val MOUSE_FLAG_WHEEL_DOWN = 0x0200

        fun mouseClickFlags(button: RemoteMouseButton, down: Boolean): Int {
            val base = when (button) {
                RemoteMouseButton.LEFT -> MOUSE_FLAG_BUTTON1
                RemoteMouseButton.RIGHT -> MOUSE_FLAG_BUTTON2
                RemoteMouseButton.MIDDLE -> MOUSE_FLAG_BUTTON3
            }
            return if (down) base or MOUSE_FLAG_DOWN else base
        }

        fun RemoteMouseButton.toLegacy(): MouseButton = when (this) {
            RemoteMouseButton.LEFT -> MouseButton.LEFT
            RemoteMouseButton.RIGHT -> MouseButton.RIGHT
            RemoteMouseButton.MIDDLE -> MouseButton.MIDDLE
        }
    }
}
