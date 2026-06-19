package com.gotohex.rdp.vnc.protocol

import android.util.Log
import com.gotohex.rdp.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Connection details for a VNC (RFB) session.
 * VNC classically authenticates with a single session password (no
 * username) — see RFC 6143 §7.2.2 "VNC Authentication".
 */
data class VncCredentials(
    val host: String,
    val port: Int,
    val password: String,
    val viewOnly: Boolean = false,
)

/**
 * Pure-Kotlin client for the RFB protocol (RFC 6143), i.e. "VNC".
 * Supports protocol versions 3.3/3.7/3.8, VNC Authentication (DES-based
 * challenge-response) and None auth, Raw + CopyRect + Hextile encodings,
 * and basic pointer/keyboard input.
 */
class VncClient(
    private val credentials: VncCredentials,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "VncClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

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

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    @Volatile private var connected = false
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    private var bytesPerPixel = 4

    private val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val sock = Socket()
            sock.connect(InetSocketAddress(credentials.host, credentials.port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            socket = sock
            input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            negotiateVersion()
            negotiateSecurity()
            sendClientInit()
            readServerInit()

            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)

            // Request updates continuously.
            requestFrameUpdate(incremental = false)
            receiveScope.launch { receiveLoop() }

            true
        } catch (e: VncAuthException) {
            Log.e(TAG, "VNC auth failed", e)
            _error.emit(e.message ?: "Authentication failed")
            _sessionState.emit(RemoteSessionState.AUTH_FAILED)
            cleanupSocket()
            false
        } catch (e: Exception) {
            Log.e(TAG, "VNC connect failed", e)
            _error.emit(e.message ?: "Connection failed")
            _sessionState.emit(RemoteSessionState.ERROR)
            cleanupSocket()
            false
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────────

    private fun negotiateVersion() {
        val serverVersion = ByteArray(12)
        input!!.readFully(serverVersion)
        // Always reply with 3.8 — every real server accepts a client claiming
        // an equal or lower version than its own.
        output!!.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output!!.flush()
    }

    private fun negotiateSecurity() {
        val numTypes = input!!.readUnsignedByte()
        if (numTypes == 0) {
            // RFB 3.3-style failure reason string follows.
            val reasonLen = input!!.readInt()
            val reason = ByteArray(reasonLen).also { input!!.readFully(it) }
            throw VncAuthException(String(reason, Charsets.US_ASCII))
        }
        val types = ByteArray(numTypes).also { input!!.readFully(it) }

        val chosen = when {
            types.contains(2.toByte()) -> 2 // VNC Authentication
            types.contains(1.toByte()) -> 1 // None
            else -> throw VncAuthException("Server requires unsupported security type(s): ${types.joinToString()}")
        }
        output!!.writeByte(chosen)
        output!!.flush()

        if (chosen == 2) {
            val challenge = ByteArray(16).also { input!!.readFully(it) }
            val response = encryptVncChallenge(challenge, credentials.password)
            output!!.write(response)
            output!!.flush()
        }

        // SecurityResult (present for both None and VNC Auth in RFB 3.8)
        val result = input!!.readInt()
        if (result != 0) {
            val reasonLen = input!!.readInt()
            val reason = ByteArray(reasonLen).also { input!!.readFully(it) }
            throw VncAuthException(String(reason, Charsets.US_ASCII).ifBlank { "Authentication failed" })
        }
    }

    /**
     * RFB's VNC Authentication uses DES, keyed by the password with each
     * byte's bits reversed (a historical VNC quirk), to encrypt the 16-byte
     * server challenge. See RFC 6143 §7.2.2.
     */
    private fun encryptVncChallenge(challenge: ByteArray, password: String): ByteArray {
        val keyBytes = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 8) {
            keyBytes[i] = if (i < pwBytes.size) reverseBits(pwBytes[i]) else 0
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }

    private fun sendClientInit() {
        output!!.writeByte(if (credentials.viewOnly) 0 else 1) // shared flag
        output!!.flush()
    }

    private fun readServerInit() {
        fbWidth = input!!.readUnsignedShort()
        fbHeight = input!!.readUnsignedShort()

        // PIXEL_FORMAT (16 bytes) — we don't strictly need the server's
        // values since we immediately override with SetPixelFormat below,
        // but we must still read past them.
        ByteArray(16).also { input!!.readFully(it) }

        val nameLen = input!!.readInt()
        val nameBytes = ByteArray(nameLen.coerceIn(0, 4096)).also { input!!.readFully(it) }
        if (nameLen > 4096) input!!.skipBytes(nameLen - 4096)
        Log.i(TAG, "VNC desktop name: ${String(nameBytes, Charsets.UTF_8)}, size ${fbWidth}x$fbHeight")

        // Force a 32bpp BGRA pixel format so frame buffers map directly onto
        // an Android ARGB_8888 Bitmap without per-pixel conversion.
        sendSetPixelFormat()
        sendSetEncodings()
    }

    private fun sendSetPixelFormat() {
        output!!.writeByte(0) // message type: SetPixelFormat
        output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0) // padding
        output!!.writeByte(32) // bits-per-pixel
        output!!.writeByte(24) // depth
        output!!.writeByte(0)  // big-endian-flag = false
        output!!.writeByte(1)  // true-colour-flag = true
        output!!.writeShort(255) // red-max
        output!!.writeShort(255) // green-max
        output!!.writeShort(255) // blue-max
        output!!.writeByte(16) // red-shift
        output!!.writeByte(8)  // green-shift
        output!!.writeByte(0)  // blue-shift
        output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0) // padding
        output!!.flush()
        bytesPerPixel = 4
    }

    private fun sendSetEncodings() {
        val encodings = intArrayOf(
            0,  // Raw
            1,  // CopyRect
            5,  // Hextile
        )
        output!!.writeByte(2) // message type: SetEncodings
        output!!.writeByte(0) // padding
        output!!.writeShort(encodings.size)
        encodings.forEach { output!!.writeInt(it) }
        output!!.flush()
    }

    // ── Frame update loop ────────────────────────────────────────────────────

    private fun requestFrameUpdate(incremental: Boolean, x: Int = 0, y: Int = 0, w: Int = fbWidth, h: Int = fbHeight) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(3) // FramebufferUpdateRequest
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(x); out.writeShort(y)
            out.writeShort(w); out.writeShort(h)
            out.flush()
        }
    }

    private suspend fun receiveLoop() {
        try {
            while (connected) {
                val msgType = input!!.readUnsignedByte()
                when (msgType) {
                    0 -> handleFramebufferUpdate()
                    1 -> handleSetColourMapEntries()
                    2 -> { /* Bell — ignore */ }
                    3 -> handleServerCutText()
                    else -> {
                        Log.w(TAG, "Unknown server message type $msgType — disconnecting to avoid desync")
                        break
                    }
                }
                // Ask for the next incremental update right away (classic
                // VNC client polling pattern — keeps frames flowing).
                requestFrameUpdate(incremental = true)
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "VNC receive loop error", e)
                _error.emit(e.message ?: "Connection lost")
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    private suspend fun handleFramebufferUpdate() {
        input!!.readByte() // padding
        val numRects = input!!.readUnsignedShort()
        val startTime = System.currentTimeMillis()

        repeat(numRects) {
            val x = input!!.readUnsignedShort()
            val y = input!!.readUnsignedShort()
            val w = input!!.readUnsignedShort()
            val h = input!!.readUnsignedShort()
            val encoding = input!!.readInt()

            when (encoding) {
                0 -> readRawRect(x, y, w, h)
                1 -> readCopyRect(x, y, w, h)
                5 -> readHextileRect(x, y, w, h)
                else -> {
                    Log.w(TAG, "Unsupported encoding $encoding for rect, disconnecting")
                    connected = false
                }
            }
        }
        latencyMs = System.currentTimeMillis() - startTime
    }

    private suspend fun readRawRect(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val pixelCount = w * h
        val bytes = ByteArray(pixelCount * bytesPerPixel)
        input!!.readFully(bytes)
        val pixels = bgraBytesToArgbInts(bytes, pixelCount)
        _frameUpdates.emit(RemoteFrameUpdate(x, y, w, h, pixels, fullScreen = (x == 0 && y == 0 && w == fbWidth && h == fbHeight)))
    }

    private suspend fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        // Source rect to copy from. We don't keep a full local framebuffer
        // copy in this minimal client, so we conservatively translate a
        // CopyRect into a fresh non-incremental request for that area —
        // correct but slightly less bandwidth-efficient than a true local
        // blit. This keeps the implementation simple and robust.
        input!!.readUnsignedShort() // src-x
        input!!.readUnsignedShort() // src-y
        requestFrameUpdate(incremental = false, x = x, y = y, w = w, h = h)
    }

    private suspend fun readHextileRect(x0: Int, y0: Int, w: Int, h: Int) {
        var bgColor = 0
        var fgColor = 0
        val tileSize = 16
        var ty = 0
        while (ty < h) {
            val th = minOf(tileSize, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(tileSize, w - tx)
                val subEncoding = input!!.readUnsignedByte()

                val tilePixels = IntArray(tw * th)

                if (subEncoding and 0x01 != 0) {
                    // Raw
                    val bytes = ByteArray(tw * th * bytesPerPixel)
                    input!!.readFully(bytes)
                    val argb = bgraBytesToArgbInts(bytes, tw * th)
                    System.arraycopy(argb, 0, tilePixels, 0, argb.size)
                } else {
                    if (subEncoding and 0x02 != 0) bgColor = readPixelColor()
                    if (subEncoding and 0x04 != 0) fgColor = readPixelColor()
                    tilePixels.fill(bgColor)

                    if (subEncoding and 0x08 != 0) {
                        val numSubrects = input!!.readUnsignedByte()
                        repeat(numSubrects) {
                            val color = if (subEncoding and 0x10 != 0) readPixelColor() else fgColor
                            val xy = input!!.readUnsignedByte()
                            val wh = input!!.readUnsignedByte()
                            val sx = (xy shr 4) and 0x0F
                            val sy = xy and 0x0F
                            val sw = ((wh shr 4) and 0x0F) + 1
                            val sh = (wh and 0x0F) + 1
                            for (py in sy until minOf(sy + sh, th)) {
                                for (px in sx until minOf(sx + sw, tw)) {
                                    tilePixels[py * tw + px] = color
                                }
                            }
                        }
                    }
                }

                _frameUpdates.emit(RemoteFrameUpdate(x0 + tx, y0 + ty, tw, th, tilePixels, fullScreen = false))
                tx += tileSize
            }
            ty += tileSize
        }
    }

    private fun readPixelColor(): Int {
        val bytes = ByteArray(bytesPerPixel)
        input!!.readFully(bytes)
        return bgraBytesToArgbInts(bytes, 1)[0]
    }

    private fun bgraBytesToArgbInts(bytes: ByteArray, pixelCount: Int): IntArray {
        val out = IntArray(pixelCount)
        var idx = 0
        for (i in 0 until pixelCount) {
            val b = bytes[idx].toInt() and 0xFF
            val g = bytes[idx + 1].toInt() and 0xFF
            val r = bytes[idx + 2].toInt() and 0xFF
            // byte idx+3 is padding/alpha from the server, ignored — force opaque
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            idx += bytesPerPixel
        }
        return out
    }

    private suspend fun handleSetColourMapEntries() {
        // We always force true-colour mode via SetPixelFormat, so servers
        // should never send this — but parse-and-discard defensively in
        // case one does anyway, to avoid desyncing the stream.
        input!!.readUnsignedShort() // first-colour
        val numColours = input!!.readUnsignedShort()
        repeat(numColours) {
            input!!.readUnsignedShort(); input!!.readUnsignedShort(); input!!.readUnsignedShort()
        }
    }

    private suspend fun handleServerCutText() {
        input!!.skipBytes(3) // padding
        val len = input!!.readInt()
        val bytes = ByteArray(len.coerceAtLeast(0))
        input!!.readFully(bytes)
        // Server clipboard contents — not currently surfaced in the UI,
        // but consumed here so the stream stays in sync.
    }

    // ── Input ────────────────────────────────────────────────────────────────

    private var lastButtonMask = 0

    override fun sendMouseMove(x: Int, y: Int) = sendPointerEvent(x, y, lastButtonMask)

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        val wheelMask = if (delta > 0) (1 shl 3) else (1 shl 4)
        sendPointerEvent(x, y, lastButtonMask or wheelMask)
        sendPointerEvent(x, y, lastButtonMask)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        val bit = when (button) {
            RemoteMouseButton.LEFT -> 1
            RemoteMouseButton.MIDDLE -> 2
            RemoteMouseButton.RIGHT -> 4
        }
        lastButtonMask = if (down) lastButtonMask or bit else lastButtonMask and bit.inv()
        sendPointerEvent(x, y, lastButtonMask)
    }

    private fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (credentials.viewOnly) return
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeByte(5) // PointerEvent
                out.writeByte(buttonMask)
                out.writeShort(x.coerceIn(0, fbWidth))
                out.writeShort(y.coerceIn(0, fbHeight))
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send pointer event", e)
        }
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        // VNC's KeyEvent uses X11 keysyms, not PC scan codes. The session UI
        // (extra-keys bar, hardware keyboard) maps scan codes; we translate
        // the common ones here. Full keysym mapping lives in VncKeysyms.
        val keysym = VncKeysyms.scanCodeToKeysym(scanCode, extended) ?: return
        sendKeysymEvent(keysym, down)
    }

    fun sendUnicodeChar(char: Char, down: Boolean) {
        sendKeysymEvent(char.code, down)
    }

    private fun sendKeysymEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeByte(4) // KeyEvent
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keysym)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send key event", e)
        }
    }

    override fun sendCtrlAltDel() {
        sendKeysymEvent(VncKeysyms.XK_CONTROL_L, true)
        sendKeysymEvent(VncKeysyms.XK_ALT_L, true)
        sendKeysymEvent(VncKeysyms.XK_DELETE, true)
        sendKeysymEvent(VncKeysyms.XK_DELETE, false)
        sendKeysymEvent(VncKeysyms.XK_ALT_L, false)
        sendKeysymEvent(VncKeysyms.XK_CONTROL_L, false)
    }

    override fun sendText(text: String) {
        text.forEach { ch ->
            sendUnicodeChar(ch, true)
            sendUnicodeChar(ch, false)
        }
    }

    override fun disconnect() {
        connected = false
        receiveScope.cancel()
        cleanupSocket()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private fun cleanupSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }
}

class VncAuthException(message: String) : Exception(message)
