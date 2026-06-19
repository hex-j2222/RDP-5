package com.gotohex.rdp.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import com.gotohex.rdp.ssh.protocol.SshKeyMap
import com.gotohex.rdp.ui.theme.*

/**
 * Interactive SSH terminal — the SSH equivalent of [com.gotohex.rdp.ui.screens.RdpCanvas],
 * but text-based rather than framebuffer-based. Renders the running output
 * stream and drives input via a hidden text field (so typed keystrokes are
 * sent as raw bytes immediately) plus a row of common terminal control keys
 * (Ctrl+C, Tab, arrows, Esc) that have no plain-text representation.
 */
@Composable
fun TerminalScreen(
    profileName: String,
    terminalText: String,
    latency: Long,
    onSendText: (String) -> Unit,
    onSendControlByte: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputBuffer by remember { mutableStateOf("") }

    // Auto-scroll to bottom whenever new output arrives.
    LaunchedEffect(terminalText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(DeepSpace.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(profileName, color = StarDust, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp)
                    Text("SSH • ${latency}ms", color = PulsarCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { onSendText(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = CometTail)
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = NovaPink)
                    }
                }
            }

            // Terminal output — monospace, green-on-black classic terminal look.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .verticalScroll(scrollState)
                    .padding(10.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        keyboardController?.show()
                    }
            ) {
                Text(
                    text = terminalText.ifEmpty { "Connecting…" },
                    color = Color(0xFF33FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }

            // Control-key row — keys with no plain-text representation.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebulaSurface)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                item { TermKeyChip("Esc") { onSendText("\u001B") } }
                item { TermKeyChip("Tab") { onSendText("\t") } }
                item { TermKeyChip("Ctrl+C") { onSendControlByte(SshKeyMap.CTRL_C) } }
                item { TermKeyChip("Ctrl+D") { onSendControlByte(SshKeyMap.CTRL_D) } }
                item { TermKeyChip("Ctrl+Z") { onSendControlByte(SshKeyMap.CTRL_Z) } }
                item { TermKeyChip("Ctrl+L") { onSendControlByte(SshKeyMap.CTRL_L) } }
                item { TermKeyChip("↑") { onSendText("\u001B[A") } }
                item { TermKeyChip("↓") { onSendText("\u001B[B") } }
                item { TermKeyChip("←") { onSendText("\u001B[D") } }
                item { TermKeyChip("→") { onSendText("\u001B[C") } }
                item { TermKeyChip("Home") { onSendText("\u001B[H") } }
                item { TermKeyChip("End") { onSendText("\u001B[F") } }
                item { TermKeyChip("|") { onSendText("|") } }
                item { TermKeyChip("/") { onSendText("/") } }
                item { TermKeyChip("~") { onSendText("~") } }
            }

            // Hidden input field: captures the system keyboard, sends each
            // character/line straight to the SSH channel, and is cleared
            // immediately after every keystroke so it never accumulates
            // (the running output above is the real "screen", same as a
            // physical terminal — this field is purely an input capture).
            BasicTextField(
                value = inputBuffer,
                onValueChange = { newValue ->
                    if (newValue.length > inputBuffer.length) {
                        onSendText(newValue.substring(inputBuffer.length))
                    } else if (newValue.length < inputBuffer.length) {
                        onSendText("\u007F") // Backspace
                    }
                    inputBuffer = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(NebulaSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                textStyle = TextStyle(color = StarDust, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PulsarCyan),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSendText("\n") }
                ),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = PulsarCyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Box(Modifier.weight(1f)) { inner() }
                    }
                }
            )
        }
    }

    // Bring up the keyboard automatically when the terminal first connects.
    LaunchedEffect(Unit) { keyboardController?.show() }
}

@Composable
private fun TermKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = StarfieldSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = StarDust,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
