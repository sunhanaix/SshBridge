package com.sunbeat.sshclient.ui.terminal

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession as TermuxTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

// ── Constants ────────────────────────────────────────────────

private const val DEFAULT_FONT_SIZE_DP = 12
private const val MIN_FONT_SIZE_PX = 8
private const val MAX_FONT_SIZE_PX = 48

// ── Control key definitions ──────────────────────────────────

private data class CtrlKey(
    val label: String,
    val bytes: ByteArray,
)

private val CTRL_KEYS_ROW1 = listOf(
    CtrlKey("Esc", byteArrayOf(0x1B)),
    CtrlKey("Tab", byteArrayOf(0x09)),
    CtrlKey("/", "/".toByteArray()),
    CtrlKey("-", "-".toByteArray()),
    CtrlKey("|", "|".toByteArray()),
    CtrlKey("↑", "[A".toByteArray()),
    CtrlKey("↓", "[B".toByteArray()),
    CtrlKey("←", "[D".toByteArray()),
    CtrlKey("→", "[C".toByteArray()),
    CtrlKey("⌫", byteArrayOf(0x7F)),
)

// ── Modifier state (shared between ControlKeyBar and TerminalViewClient) ──

/** Mutable holder for sticky modifier keys, read by [TerminalViewClientImpl]. */
class ModifierState {
    @Volatile var ctrlActive: Boolean = false
    @Volatile var altActive: Boolean = false

    fun release() {
        ctrlActive = false
        altActive = false
    }
}

private val CTRL_KEYS_ROW2 = listOf(
    CtrlKey("Home", "[H".toByteArray()),
    CtrlKey("End", "[F".toByteArray()),
    CtrlKey("PgUp", "[5~".toByteArray()),
    CtrlKey("PgDn", "[6~".toByteArray()),
    CtrlKey("Ins", "[2~".toByteArray()),
    CtrlKey("F1", "OP".toByteArray()),
    CtrlKey("F2", "OQ".toByteArray()),
    CtrlKey("F3", "OR".toByteArray()),
    CtrlKey("F4", "OS".toByteArray()),
    CtrlKey("F5", "[15~".toByteArray()),
)

// ── Screen composable ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current
    val fontSizePx = with(density) { DEFAULT_FONT_SIZE_DP.dp.toPx().toInt() }
    val focusRequester = remember { FocusRequester() }

    // Track whether the control-key bar is visible
    var showCtrlBar by remember { mutableStateOf(true) }

    // Shared modifier state between ControlKeyBar (toggle UI) and TerminalViewClientImpl (I/O)
    val modifierState = remember { ModifierState() }
    var modifierVersion by remember { mutableStateOf(0) }

    // Auto-request focus when connected (triggers keyboard)
    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is ConnectionState.Connected) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
                // FocusRequester may throw if view not yet attached
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.sessionName) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCtrlBar = !showCtrlBar }) {
                        Text(
                            text = if (showCtrlBar) "−⌨" else "+⌨",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (showCtrlBar && uiState.connectionState is ConnectionState.Connected) {
                ControlKeyBar(
                    modifierState = modifierState,
                    modifierVersion = modifierVersion,
                    onModifierChanged = { modifierVersion++ },
                    onKey = { bytes -> viewModel.terminalSession?.write(bytes) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when (val state = uiState.connectionState) {
                is ConnectionState.Disconnected -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Disconnected", style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { viewModel.reconnect() }) {
                                Text("Reconnect")
                            }
                        }
                    }
                }

                is ConnectionState.Connecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                state.step,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                }

                is ConnectionState.Connected -> {
                    val terminalSession = viewModel.terminalSession

                    AndroidView(
                        factory = { ctx ->
                            createTerminalView(ctx, fontSizePx, terminalSession, modifierState)
                        },
                        update = { view ->
                            if (terminalSession != null) {
                                view.mEmulator = terminalSession.emulator
                                wireEmulatorCallback(view, terminalSession)
                                terminalSession.onEmulatorUpdated = {
                                    Handler(Looper.getMainLooper()).post { view.postInvalidate() }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester),
                    )
                }

                is ConnectionState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { viewModel.reconnect() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Control key bar ──────────────────────────────────────────

@Composable
private fun ControlKeyBar(
    modifierState: ModifierState,
    modifierVersion: Int,
    onModifierChanged: () -> Unit,
    onKey: (ByteArray) -> Unit,
) {
    // Read modifierVersion to force recomposition on toggle
    @Suppress("UNUSED_VARIABLE") val modVer = modifierVersion

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            // Row 1: Ctrl toggle, Alt toggle, Esc, Tab, /, -, |, arrows, backspace
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Ctrl toggle button
                ModifierToggleButton(
                    label = "Ctrl",
                    active = modifierState.ctrlActive,
                    onClick = {
                        modifierState.ctrlActive = !modifierState.ctrlActive
                        modifierState.altActive = false
                        onModifierChanged()
                    },
                )
                // Alt toggle button
                ModifierToggleButton(
                    label = "Alt",
                    active = modifierState.altActive,
                    onClick = {
                        modifierState.altActive = !modifierState.altActive
                        modifierState.ctrlActive = false
                        onModifierChanged()
                    },
                )
                CTRL_KEYS_ROW1.forEach { key ->
                    CtrlKeyButton(key.label) {
                        onKey(key.bytes)
                    }
                }
            }
            // Row 2: Home, End, PgUp, PgDn, Ins, F1-F5
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CTRL_KEYS_ROW2.forEach { key ->
                    CtrlKeyButton(key.label) {
                        onKey(key.bytes)
                    }
                }
            }
            // Row 3: common Ctrl+key combos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CtrlKeyButton("C-c") { onKey(byteArrayOf(0x03)) }
                CtrlKeyButton("C-d") { onKey(byteArrayOf(0x04)) }
                CtrlKeyButton("C-z") { onKey(byteArrayOf(0x1A)) }
                CtrlKeyButton("C-l") { onKey(byteArrayOf(0x0C)) }
                CtrlKeyButton("C-a") { onKey(byteArrayOf(0x01)) }
                CtrlKeyButton("C-e") { onKey(byteArrayOf(0x05)) }
                CtrlKeyButton("C-w") { onKey(byteArrayOf(0x17)) }
                CtrlKeyButton("C-u") { onKey(byteArrayOf(0x15)) }
                CtrlKeyButton("C-k") { onKey(byteArrayOf(0x0B)) }
                CtrlKeyButton("C-r") { onKey(byteArrayOf(0x12)) }
            }
        }
    }
}

@Composable
private fun ModifierToggleButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (active) "$label●" else label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun CtrlKeyButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ── TerminalView wiring ──────────────────────────────────────

/**
 * Creates and wires a [TerminalView] backed by the given [terminalSession].
 *
 * ## Integration approach
 *
 * [TerminalView] requires a non-null [TermuxTerminalSession] for keyboard
 * handling and null-safety of its `mTermSession` field.  We satisfy this by:
 *
 * 1. Creating a dummy [TermuxTerminalSession] that spawns a harmless local
 *    shell (it never receives real input).
 * 2. Calling [TerminalView.attachSession] which sets `mTermSession` to the
 *    dummy and initialises rendering.
 * 3. Replacing `mEmulator` with **our** SSH-backed emulator.
 *
 * The dummy session is only there to keep TerminalView's internal null-checks
 * happy.  All real I/O goes through our domain [TerminalSession].
 */
private fun createTerminalView(
    context: Context,
    fontSizePx: Int,
    terminalSession: com.sunbeat.sshclient.domain.ssh.TerminalSession?,
    modifierState: ModifierState,
): TerminalView {
    val view = TerminalView(context, null)

    // Critical: view must be focusable for IME to work
    view.isFocusable = true
    view.isFocusableInTouchMode = true

    // MUST set the renderer before attachSession() because updateSize()
    // immediately reads font metrics from it.
    view.mRenderer = TerminalRenderer(fontSizePx, Typeface.MONOSPACE)

    // ── Dummy Termux session ──────────────────────────────────

    val dummySession = TermuxTerminalSession(
        "/system/bin/sh",
        "/",
        emptyArray(),
        null,
        24,
        NoopTerminalSessionClient,
    )

    // attachSession sets mTermSession (non-null for null checks) and calls
    // updateSize() which initialises mEmulator from the session.
    view.attachSession(dummySession)

    // ── Swap in our emulator ──────────────────────────────────

    if (terminalSession != null) {
        view.mEmulator = terminalSession.emulator
        view.setTerminalViewClient(
            TerminalViewClientImpl(view, terminalSession, modifierState, fontSizePx),
        )
        wireEmulatorCallback(view, terminalSession)
    }

    // ── Handle view resizes ───────────────────────────────────
    // updateSize() is called automatically by onSizeChanged, but it only
    // operates on the dummy session.  We intercept the result to keep our
    // emulator's dimensions in sync.

    view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val w = right - left
        val h = bottom - top
        val ts = terminalSession
        val r = view.mRenderer ?: return@addOnLayoutChangeListener
        if (w > 0 && h > 0 && ts != null && r.getFontWidth() > 0f && r.getFontLineSpacing() > 0) {
            val cols = (w.toFloat() / r.getFontWidth()).toInt().coerceAtLeast(4)
            val rows = ((h.toFloat() - r.getFontLineSpacing()) / r.getFontLineSpacing()).toInt().coerceAtLeast(4)
            val emu = ts.emulator
            if (emu.mColumns != cols || emu.mRows != rows) {
                ts.resize(rows, cols)
            }
            // Always restore our emulator — TerminalView.onSizeChanged()
            // calls updateSize() which resets mEmulator to the dummy session.
            view.mEmulator = emu
        }
    }

    view.setTopRow(0)
    view.onScreenUpdated()

    // Auto-show keyboard after the view is attached
    view.post {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    return view
}

/**
 * Wires the terminal emulator's [TerminalSessionClient] so that screen changes
 * trigger a View redraw via [TerminalView.postInvalidate] (thread-safe).
 */
private fun wireEmulatorCallback(
    terminalView: TerminalView,
    terminalSession: com.sunbeat.sshclient.domain.ssh.TerminalSession,
) {
    terminalSession.emulator.updateTerminalSessionClient(
        object : TerminalSessionClient {
            override fun onTextChanged(session: TermuxTerminalSession?) {
                Log.d("SSHTerm", "onTextChanged → postInvalidate")
                terminalView.postInvalidate()
            }
            override fun onTitleChanged(session: TermuxTerminalSession?) {}
            override fun onSessionFinished(session: TermuxTerminalSession?) {}
            override fun onCopyTextToClipboard(session: TermuxTerminalSession?, text: String?) {}
            override fun onPasteTextFromClipboard(session: TermuxTerminalSession?) {}
            override fun onBell(session: TermuxTerminalSession?) {}
            override fun onColorsChanged(session: TermuxTerminalSession?) {}
            override fun onTerminalCursorStateChange(enabled: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {
                Log.e(tag ?: "TEmu", message ?: "")
            }
            override fun logWarn(tag: String?, message: String?) {
                Log.w(tag ?: "TEmu", message ?: "")
            }
            override fun logInfo(tag: String?, message: String?) {
                Log.i(tag ?: "TEmu", message ?: "")
            }
            override fun logDebug(tag: String?, message: String?) {
                Log.d(tag ?: "TEmu", message ?: "")
            }
            override fun logVerbose(tag: String?, message: String?) {
                Log.v(tag ?: "TEmu", message ?: "")
            }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                Log.e(tag ?: "TEmu", "$message: ${e?.message}")
            }
            override fun logStackTrace(tag: String?, message: Exception?) {
                Log.e(tag ?: "TEmu", message?.message ?: "")
            }
        },
    )
}

/** Recalculate emulator columns/rows to match the current view dimensions. */
private fun resizeEmulator(
    terminalView: TerminalView,
    terminalSession: com.sunbeat.sshclient.domain.ssh.TerminalSession,
) {
    val r = terminalView.mRenderer ?: return
    val w = terminalView.width
    val h = terminalView.height
    val fw = r.getFontWidth()
    val lh = r.getFontLineSpacing()
    if (w <= 0 || h <= 0 || fw <= 0f || lh <= 0) return

    val cols = (w.toFloat() / fw).toInt().coerceAtLeast(4)
    val rows = ((h.toFloat() - lh) / lh).toInt().coerceAtLeast(4)

    val emu = terminalSession.emulator
    if (emu.mColumns != cols || emu.mRows != rows) {
        terminalSession.resize(rows, cols)
    }
}

// ── TerminalViewClient ───────────────────────────────────────

/**
 * Routes all keyboard and touch events to the domain [TerminalSession] (which
 * writes to the SSH channel).
 *
 * ## Key handling strategy
 *
 * - **Special keys** (arrows, function keys, Ctrl+letter): converted via
 *   [KeyHandler.getCode] into terminal escape sequences.
 * - **Printing keys**: the Unicode code point is extracted and encoded as UTF-8.
 * - **Soft keyboard** characters arrive via [onCodePoint] and are written as UTF-8.
 * - All `onKeyDown` calls return `true` (consumed) to prevent [TerminalView] from
 *   falling through to its default handler which would write to the dummy local
 *   session.
 */
private class TerminalViewClientImpl(
    private val terminalView: TerminalView,
    private val terminalSession: com.sunbeat.sshclient.domain.ssh.TerminalSession?,
    private val modifierState: ModifierState,
    initialFontSizePx: Int,
) : TerminalViewClient {

    private var currentFontSizePx: Int = initialFontSizePx.coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)

    // ── Scale / Zoom ──────────────────────────────────────────

    override fun onScale(scale: Float): Float {
        val newSize = (currentFontSizePx * scale).toInt()
            .coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        if (newSize == currentFontSizePx) return 1f

        val actualScale = newSize.toFloat() / currentFontSizePx.toFloat()
        currentFontSizePx = newSize

        terminalView.setTextSize(newSize)

        // setTextSize recreates the renderer and calls updateSize() which
        // overwrites mEmulator — restore ours and recalculate dimensions.
        if (terminalSession != null) {
            terminalView.mEmulator = terminalSession.emulator
            resizeEmulator(terminalView, terminalSession)
            terminalView.onScreenUpdated()
        }
        return actualScale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        Log.d("SSHTerm", "onSingleTapUp → requestFocus + showSoftInput")
        terminalView.requestFocus()
        val imm = terminalView.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onLongPress(e: MotionEvent): Boolean {
        val clipboard = terminalView.context
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(terminalView.context)
            if (!text.isNullOrEmpty()) {
                terminalSession?.write(text.toString())
            }
        }
        return true
    }

    // ── Key events ────────────────────────────────────────────

    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent,
        session: TermuxTerminalSession?,
    ): Boolean {
        // 1. Try KeyHandler for special keys (arrows, function keys, Ctrl+letter).
        //    readControlKey()/readAltKey() now return sticky modifier state.
        val seq = KeyHandler.getCode(keyCode, e.metaState, readAltKey(), readControlKey())
        if (seq != null) {
            modifierState.release()
            terminalSession?.write(seq.toByteArray())
            return true
        }

        // 2. Printing key — extract the Unicode code point.
        val unicode = e.getUnicodeChar(e.metaState)
        if (unicode != 0) {
            val handled = writeWithModifiers(unicode)
            if (!handled) {
                val bytes = try {
                    String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return true
                }
                terminalSession?.write(bytes)
            }
            return true
        }

        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onCodePoint(
        codePoint: Int,
        controlDown: Boolean,
        session: TermuxTerminalSession?,
    ): Boolean {
        val effectiveCtrl = controlDown || modifierState.ctrlActive
        val effectiveAlt = modifierState.altActive
        val hasModifier = effectiveCtrl || effectiveAlt

        // Ctrl+letter (a-z) → ASCII control char 0x01–0x1A
        if (effectiveCtrl && codePoint in 'a'.code..'z'.code) {
            modifierState.release()
            terminalSession?.write(byteArrayOf((codePoint - 'a'.code + 1).toByte()))
            return true
        }
        if (effectiveCtrl && codePoint in 'A'.code..'Z'.code) {
            modifierState.release()
            terminalSession?.write(byteArrayOf((codePoint - 'A'.code + 1).toByte()))
            return true
        }

        // Ctrl+any ASCII printable → (codePoint & 0x1F)
        // Covers: @→0x00, [→0x1B, \→0x1C, ]→0x1D, ^→0x1E, _→0x1F, ?→0x7F
        if (effectiveCtrl && codePoint in 0x20..0x7F) {
            modifierState.release()
            terminalSession?.write(byteArrayOf((codePoint and 0x1F).toByte()))
            return true
        }

        // Alt+ASCII printable → ESC prefix
        if (effectiveAlt && codePoint in 0x20..0x7E) {
            modifierState.release()
            val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            terminalSession?.write(byteArrayOf(0x1B) + charBytes)
            return true
        }

        // Non-ASCII or no modifier → release any latched modifier and write raw UTF-8.
        // Chinese/emoji/etc. pass through unchanged.
        if (hasModifier) {
            modifierState.release()
        }
        val bytes = try {
            String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return true
        }
        terminalSession?.write(bytes)
        return true
    }

    // ── Modifier / config ─────────────────────────────────────

    override fun readControlKey(): Boolean = modifierState.ctrlActive
    override fun readAltKey(): Boolean = modifierState.altActive
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onEmulatorSet() {}

    // ── Helpers ───────────────────────────────────────────────

    /** Apply sticky modifiers to a Unicode code point from onKeyDown. */
    private fun writeWithModifiers(codePoint: Int): Boolean {
        val ts = terminalSession ?: return false

        if (modifierState.ctrlActive && codePoint in 'a'.code..'z'.code) {
            modifierState.release()
            ts.write(byteArrayOf((codePoint - 'a'.code + 1).toByte()))
            return true
        }
        if (modifierState.ctrlActive && codePoint in 'A'.code..'Z'.code) {
            modifierState.release()
            ts.write(byteArrayOf((codePoint - 'A'.code + 1).toByte()))
            return true
        }
        if (modifierState.ctrlActive && codePoint in 0x20..0x7F) {
            modifierState.release()
            ts.write(byteArrayOf((codePoint and 0x1F).toByte()))
            return true
        }
        if (modifierState.altActive && codePoint in 0x20..0x7E) {
            modifierState.release()
            val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            ts.write(byteArrayOf(0x1B) + charBytes)
            return true
        }
        // Non-ASCII or no modifier active
        if (modifierState.ctrlActive || modifierState.altActive) {
            modifierState.release()
        }
        return false
    }

    // ── Logging ───────────────────────────────────────────────

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: "TerminalView", message ?: "")
    }
    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: "TerminalView", message ?: "")
    }
    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: "TerminalView", message ?: "")
    }
    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: "TerminalView", message ?: "")
    }
    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: "TerminalView", message ?: "")
    }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: "TerminalView", "$message: ${e?.message}")
    }
    override fun logStackTrace(tag: String?, message: Exception?) {
        Log.e(tag ?: "TerminalView", message?.message ?: "")
    }
}

// ── No-op TerminalSessionClient ──────────────────────────────

/** Shared no-op client used when constructing the dummy [TermuxTerminalSession]. */
private val NoopTerminalSessionClient = object : TerminalSessionClient {
    override fun onTextChanged(session: TermuxTerminalSession?) = Unit
    override fun onTitleChanged(session: TermuxTerminalSession?) = Unit
    override fun onSessionFinished(session: TermuxTerminalSession?) = Unit
    override fun onCopyTextToClipboard(session: TermuxTerminalSession?, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TermuxTerminalSession?) = Unit
    override fun onBell(session: TermuxTerminalSession?) = Unit
    override fun onColorsChanged(session: TermuxTerminalSession?) = Unit
    override fun onTerminalCursorStateChange(enabled: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, message: Exception?) = Unit
}
