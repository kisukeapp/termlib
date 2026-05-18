/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Terminal emulator interface. This has no dependency on any UI framework
 * so it may be run in a Service on Android. It handles the management of
 * the terminal emulation state.
 */
sealed interface TerminalEmulator {
    /**
     * Write data to the terminal (from PTY/transport).
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    fun writeInput(buffer: ByteBuffer, length: Int)

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int)

    /**
     * Dispatch a key event to the terminal.
     */
    fun dispatchKey(modifiers: Int, key: Int)

    /**
     * Dispatch a character to the terminal.
     */
    @Deprecated(
        message = "Use dispatchCharacter(modifiers, codepoint) for full Unicode code point support",
        replaceWith = ReplaceWith("dispatchCharacter(modifiers, ch.code)"),
    )
    fun dispatchCharacter(modifiers: Int, ch: Char) = dispatchCharacter(modifiers, ch.code)

    /**
     * Dispatch a character to the terminal.
     */
    fun dispatchCharacter(modifiers: Int, codepoint: Int)

    /**
     * Clears the terminal emulator screen.
     */
    fun clearScreen()

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    fun setAnsiPalette(ansiColors: IntArray): Int

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    fun setDefaultColors(foreground: Int, background: Int): Int

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    )

    /**
     * Dispatch mouse move event to the terminal.
     * Only sends to libvterm when mouse mode is active.
     */
    fun mouseMove(row: Int, col: Int, modifiers: Int)

    /**
     * Dispatch mouse button event to the terminal.
     * Only sends to libvterm when mouse mode is active.
     */
    fun mouseButton(button: Int, pressed: Boolean, modifiers: Int)

    /**
     * Paste text with bracketed paste support.
     * Wraps text in bracketed paste sequences when mode 2004 is active.
     */
    fun paste(text: String)

    /**
     * Notify terminal of focus gain.
     */
    fun focusIn()

    /**
     * Notify terminal of focus loss.
     */
    fun focusOut()

    /**
     * Current mouse mode (NONE, CLICK, DRAG, MOVE).
     */
    val mouseMode: MouseMode

    /**
     * Whether kitty keyboard protocol is active.
     */
    val kittyKeyboardActive: Boolean

    /**
     * Whether the terminal is currently using the alternate screen buffer
     * (DECSET 1049 / VTERM_PROP_ALTSCREEN).
     *
     * TUI apps like vim, less, htop, lazygit switch to the alternate screen
     * so the user's shell history is preserved on exit. The UI layer uses
     * this to decide whether scroll gestures should navigate the local
     * scrollback (normal screen) or be forwarded to the running TUI as
     * cursor key events (alternate screen with no mouse mode).
     */
    val isAltScreenActive: Boolean

    val dimensions: TerminalDimensions

    /**
     * Whether plain-text URL auto-detection is enabled.
     *
     * When true, [TerminalLine.getHyperlinkUrlAt] will scan line text for URLs in addition
     * to OSC 8 hyperlink segments. When false, only OSC 8 segments are used.
     */
    val autoDetectUrls: Boolean

    /**
     * Whether bold text using low-intensity ANSI colors (0–7) promotes to the
     * corresponding bright palette color (8–15), matching xterm's boldColors behavior.
     */
    val boldAsBright: Boolean

    /**
     * Get the text output of the last completed command.
     *
     * Uses OSC 133 semantic segments to find the boundaries of the most recent
     * completed command output. Requires shell integration (OSC 133) to be
     * enabled in the user's shell.
     *
     * @return The command output text, or null if no completed command is found
     */
    fun getLastCommandOutput(): String?
}

/**
 * Mouse tracking modes matching libvterm VTERM_PROP_MOUSE values.
 */
enum class MouseMode {
    NONE,
    CLICK,
    DRAG,
    MOVE
}

class TerminalEmulatorFactory {
    companion object {
        /**
         * Creates the default implementation of TerminalEmulator.
         *
         * @param looper The Looper to use for callback handling (typically main looper)
         * @param initialRows Initial number of rows
         * @param initialCols Initial number of columns
         * @param defaultForeground Default foreground color
         * @param defaultBackground Default background color
         * @param onKeyboardInput Callback for keyboard output (to write to PTY)
         * @param onBell Optional callback for terminal bell
         * @param onResize Optional callback for terminal resize
         * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations.
         *                        The callback receives the decoded text to copy.
         * @param onProgressChange Optional callback for OSC 9;4 progress reporting.
         *                         The callback receives the progress state and percentage (0-100).
         * @param onCommandLineChanged Optional callback for command line changes detected via
         *                             native screen readback (OSC 133 shell integration).
         *                             Receives the command text and cursor offset within it.
         * @param autoDetectUrls Whether to scan terminal line text for plain-text URLs and expose
         *                       them via [TerminalLine.getHyperlinkUrlAt] as a fallback when no
         *                       OSC 8 hyperlink covers the column. Defaults to false.
         * @param boldAsBright Whether bold text using low-intensity ANSI colors (0–7) promotes to
         *                     the corresponding bright palette color (8–15), matching xterm's
         *                     default boldColors behavior. Defaults to true.
         */
        fun create(
            looper: Looper = Looper.getMainLooper(),
            initialRows: Int = 24,
            initialCols: Int = 80,
            defaultForeground: Color = Color.White,
            defaultBackground: Color = Color.Black,
            onKeyboardInput: (ByteArray) -> Unit = {},
            onBell: (() -> Unit)? = null,
            onResize: ((TerminalDimensions) -> Unit)? = null,
            onClipboardCopy: ((String) -> Unit)? = null,
            onProgressChange: ((ProgressState, Int) -> Unit)? = null,
            onCommandLineChanged: ((String, Int) -> Unit)? = null,
            autoDetectUrls: Boolean = false,
            boldAsBright: Boolean = true,
        ): TerminalEmulator = TerminalEmulatorImpl(
            looper = looper,
            initialRows = initialRows,
            initialCols = initialCols,
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            onKeyboardInput = onKeyboardInput,
            onBell = onBell,
            onResize = onResize,
            onClipboardCopy = onClipboardCopy,
            onProgressChange = onProgressChange,
            onCommandLineChanged = onCommandLineChanged,
            autoDetectUrls = autoDetectUrls,
            boldAsBright = boldAsBright,
        )
    }
}

/**
 * Service-compatible terminal state manager.
 *
 * This class manages terminal state independently of the UI layer, making it
 * suitable for running in a background Android Service. It wraps the native
 * Terminal implementation and exposes state changes via StateFlow.
 *
 * Key features:
 * - No Compose dependencies (can run in background Service)
 * - Thread-safe callback handling with proper synchronization
 * - Snapshot-based state emission via StateFlow
 * - Accumulates damage and escapes native mutex before processing
 *
 * Threading model:
 * - JNI callbacks run on native thread and accumulate damage
 * - Handler posts to specified Looper to escape native mutex
 * - Snapshot building happens on Handler thread
 * - StateFlow emission is thread-safe
 *
 * @param looper The Looper to use for callback handling (typically main looper)
 * @param initialRows Initial number of rows
 * @param initialCols Initial number of columns
 * @param defaultForeground Default foreground color
 * @param defaultBackground Default background color
 * @param onKeyboardInput Callback for keyboard output (to write to PTY)
 * @param onBell Optional callback for terminal bell
 * @param onResize Optional callback for terminal resize
 * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations
 * @param onProgressChange Optional callback for OSC 9;4 progress reporting
 */
internal class TerminalEmulatorImpl(
    private val looper: Looper = Looper.getMainLooper(),
    initialRows: Int = 24,
    initialCols: Int = 80,
    defaultForeground: Color = Color.White,
    defaultBackground: Color = Color.Black,
    private val onKeyboardInput: (ByteArray) -> Unit = {},
    private val onBell: (() -> Unit)? = null,
    private val onResize: ((TerminalDimensions) -> Unit)? = null,
    private val onClipboardCopy: ((String) -> Unit)? = null,
    private val onProgressChange: ((ProgressState, Int) -> Unit)? = null,
    private val onCommandLineChanged: ((String, Int) -> Unit)? = null,
    override val autoDetectUrls: Boolean = false,
    override val boldAsBright: Boolean = true,
) : TerminalEmulator,
    TerminalCallbacks {

    // Handler for escaping native mutex
    private val handler = Handler(looper)

    // Default colors (can be updated via setDefaultColors)
    private var currentDefaultForeground: Color = defaultForeground
    private var currentDefaultBackground: Color = defaultBackground

    // Damage accumulation (thread-safe) - MUST be initialized before terminalNative
    private val damageLock = Object()
    private val pendingDamageRegions = mutableListOf<DamageRegion>()
    private var damagePosted = false
    private var cursorMoved = false
    private var propertyChanged = false

    // Pending semantic segments to apply during processPendingUpdates
    private val pendingSemanticSegments = mutableListOf<PendingSemanticSegment>()

    // StateFlow for reactive state propagation
    private val _snapshot = MutableStateFlow(
        TerminalSnapshot.empty(initialRows, initialCols, currentDefaultForeground, currentDefaultBackground),
    )
    internal val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // Sequence number for ordering snapshots
    private var sequenceNumber = 0L

    // Command line change tracking for callback deduplication
    private var lastCommandLineText: String? = null
    private var lastCommandLineCursor: Int = -1

    // Terminal dimensions
    override val dimensions: TerminalDimensions
        get() = TerminalDimensions(rows = rows, columns = cols)

    private var rows = initialRows
    private var cols = initialCols

    // Cursor state
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var cursorShape = CursorShape.BLOCK
    private var cursorBlink = false

    // Terminal properties
    private var terminalTitle = ""

    // Mouse mode tracking (set via setTermProp with VTERM_PROP_MOUSE)
    private var _mouseMode = MouseMode.NONE
    override val mouseMode: MouseMode get() = _mouseMode

    // Alternate screen tracking (set via setTermProp with VTERM_PROP_ALTSCREEN, value 3)
    @Volatile
    private var _altScreenActive: Boolean = false
    override val isAltScreenActive: Boolean get() = _altScreenActive

    // Synchronized output mode (CSI ? 2026 h/l)
    private var syncOutputActive = false
    private val syncOutputHandler = Handler(looper)
    private val syncOutputTimeout = Runnable { flushSyncOutput() }
    private val SYNC_OUTPUT_TIMEOUT_MS = 1000L

    // Kitty keyboard protocol
    private val kittyKeyboardStack = mutableListOf<Int>()
    override val kittyKeyboardActive: Boolean get() = kittyKeyboardStack.isNotEmpty()

    // Scrollback buffer
    private val scrollback = mutableListOf<TerminalLine>()
    private val maxScrollbackLines = 1000

    // Cached immutable copy of scrollback - only recreate when scrollback changes
    private var scrollbackSnapshot: List<TerminalLine> = emptyList()
    private var scrollbackDirty = false

    // Reusable CellRun for fetching cell data
    private val cellRun = CellRun()

    // Current screen lines cache
    private var currentLines = List(initialRows) { row ->
        TerminalLine.empty(row, initialCols, currentDefaultForeground, currentDefaultBackground)
    }

    // Guard resize reflow from scrollback callbacks
    // Guarded by damageLock.
    @Volatile
    private var resizing = false
    private var resizeAllowPop = true
    // Scrollback snapshot used during resize; mutated by pop callbacks.
    // Guarded by damageLock.
    private var resizeScrollback: MutableList<TerminalLine> = mutableListOf()
    private var resizePushedLines: MutableList<TerminalLine> = mutableListOf()

    // Serializes all access to libvterm via terminalNative.
    // libvterm is NOT thread-safe: writeInput() (transport thread) and resize() (main
    // thread) must not call into it concurrently. Lock ordering: nativeLock → damageLock
    // (never the reverse) to prevent deadlocks.
    private val nativeLock = Any()

    // Native terminal instance - MUST be initialized AFTER damageLock and other state
    private val terminalNative by lazy {
        TerminalNative(this).apply {
            resize(initialRows, initialCols)
            if (setBoldHighbright(boldAsBright) != 0) {
                Log.e(TAG, "Failed to set boldAsBright=$boldAsBright")
            }
        }
    }

    // Parser for OSC sequences
    private val oscParser = OscParser()

    // Image manager for inline images (iTerm2 OSC 1337, Sixel, Kitty graphics)
    private val imageManager = ImageManager()

    // Sixel decoder for DCS 'q' sequences
    private val sixelDecoder = SixelDecoder()

    // Search engine
    private val searchEngine = SearchEngine()

    // Implicit link detector
    private val linkDetector = ImplicitLinkDetector()

    // Kitty graphics handler
    private val kittyGraphics = KittyGraphics(imageManager)

    // ================================================================================
    // Public API
    // ================================================================================

    /**
     * Write data to the terminal (from PTY/transport).
     */
    override fun writeInput(data: ByteArray, offset: Int, length: Int) {
        synchronized(nativeLock) {
            terminalNative.writeInput(data, offset, length)
        }
    }

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    override fun writeInput(buffer: ByteBuffer, length: Int) {
        synchronized(nativeLock) {
            terminalNative.writeInput(buffer, length)
        }
    }

    /**
     * Resize the terminal.
     */
    override fun resize(newRows: Int, newCols: Int) {
        val oldCols = cols
        val oldRows = rows
        val colsChanged = newCols != oldCols

        val preResizeScrollback: List<TerminalLine>
        val preResizeScreenLines: List<TerminalLine>
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        val preReflowedScrollback: List<TerminalLine>
        synchronized(damageLock) {
            resizing = true
            resizeAllowPop = !(newCols < oldCols && newRows <= oldRows)
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
            preResizeScrollback = scrollback.toList()
            preResizeScreenLines = currentLines.toList()
            resizePushedLines.clear()
            pendingDamageRegions.clear()
            damagePosted = false
        }

        // Pre-reflow scrollback to new column width. This serves as the pop buffer
        // during native resize — libvterm requests popped lines at new_cols.
        preReflowedScrollback = if (colsChanged && preResizeScrollback.isNotEmpty()) {
            reflowLines(
                lines = stripOldWidthPadding(
                    preResizeScrollback,
                    oldCols,
                    currentDefaultFg,
                    currentDefaultBg
                ),
                newCols = newCols,
                defaultFg = currentDefaultFg,
                defaultBg = currentDefaultBg
            )
        } else {
            preResizeScrollback
        }

        synchronized(damageLock) {
            resizeScrollback.clear()
            resizeScrollback.addAll(preReflowedScrollback)
        }

        rows = newRows
        cols = newCols

        // Hold nativeLock for the entire resize+read sequence so no writeInput()
        // can interleave between the native resize and our screen read.
        synchronized(nativeLock) {
            // libvterm reflows its screen (reflow is enabled). libvterm is authoritative
            // for screen content — it handles wrapping, spare-row usage, and cursor
            // positioning correctly. Kotlin only reflows scrollback.
            terminalNative.resize(newRows, newCols)

            // Read ALL screen rows from libvterm — this is the authoritative screen state.
            var screenLines = List(newRows) { row ->
                readLineFromLibvterm(row, preserveTrailingSpaces = false)
            }

            synchronized(damageLock) {
                // Assemble scrollback from the push/pop protocol:
                // - resizeScrollback = pop buffer remainder (what wasn't popped)
                // - resizePushedLines = actual data from push callbacks (at old width)
                val pushedLines = resizePushedLines.toList()

                val newScrollback = if (pushedLines.isEmpty()) {
                    // No pushes — scrollback is just what wasn't popped
                    resizeScrollback.toList()
                } else if (colsChanged) {
                    // Reflow pop buffer remainder + pushed lines TOGETHER.
                    // This preserves logical lines that span the boundary
                    // (pushed lines with continuation=true join with scrollback).
                    reflowLines(
                        lines = resizeScrollback + pushedLines,
                        newCols = newCols,
                        defaultFg = currentDefaultFg,
                        defaultBg = currentDefaultBg
                    )
                } else {
                    // No column change — just concatenate
                    resizeScrollback + pushedLines
                }

                val boundedScrollback = if (newScrollback.size > maxScrollbackLines) {
                    newScrollback.takeLast(maxScrollbackLines)
                } else {
                    newScrollback
                }

                // When only rows change, libvterm can reset continuation flags on the
                // existing screen lines that shift down. Restore them from the
                // pre-resize screen snapshot to keep logical lines intact.
                if (!colsChanged && newRows > oldRows && preResizeScreenLines.isNotEmpty()) {
                    val offset = newRows - oldRows
                    if (offset in 1..screenLines.size) {
                        val restored = screenLines.toMutableList()
                        val maxRestore = minOf(preResizeScreenLines.size, screenLines.size - offset)
                        for (i in 0 until maxRestore) {
                            val newIndex = i + offset
                            restored[newIndex] = restored[newIndex].copy(
                                continuation = preResizeScreenLines[i].continuation
                            )
                        }
                        screenLines = restored
                    }
                }

                scrollback.clear()
                scrollback.addAll(boundedScrollback)
                scrollbackDirty = true

                currentLines = screenLines

                resizeScrollback.clear()
                resizePushedLines.clear()
                pendingDamageRegions.clear()
                damagePosted = false

                resizing = false
                resizeAllowPop = true
            }

            // Sync any corrected continuation flags back to libvterm.
            if (colsChanged) {
                for (row in 0 until newRows) {
                    terminalNative.setLineContinuation(row, currentLines[row].continuation)
                }
            }
        }

        val newSnapshot = buildSnapshot()
        _snapshot.value = newSnapshot

        handler.post {
            onResize?.invoke(TerminalDimensions(rows = rows, columns = cols))
        }
    }


    /**
     * Dispatch a key event to the terminal.
     */
    override fun dispatchKey(modifiers: Int, key: Int) {
        synchronized(nativeLock) {
            terminalNative.dispatchKey(modifiers, key)
        }
    }

    /**
     * Dispatch a character to the terminal.
     */
    override fun dispatchCharacter(modifiers: Int, codepoint: Int) {
        terminalNative.dispatchCharacter(modifiers, codepoint)
    }

    override fun mouseMove(row: Int, col: Int, modifiers: Int) {
        if (_mouseMode == MouseMode.NONE) return
        terminalNative.mouseMove(row, col, modifiers)
    }

    override fun mouseButton(button: Int, pressed: Boolean, modifiers: Int) {
        if (_mouseMode == MouseMode.NONE) return
        terminalNative.mouseButton(button, pressed, modifiers)
    }

    override fun paste(text: String) {
        if (text.isEmpty()) return
        terminalNative.startPaste()
        for (char in text) {
            terminalNative.dispatchCharacter(0, char.code)
        }
        terminalNative.endPaste()
    }

    override fun focusIn() {
        terminalNative.focusIn()
    }

    override fun focusOut() {
        terminalNative.focusOut()
    }

    /**
     * Clears the terminal emulator screen.
     */
    override fun clearScreen() = writeInput("\u001B[2J\u001B[H".toByteArray())

    /**
     * Get the text output of the last completed command.
     */
    override fun getLastCommandOutput(): String? {
        val currentSnapshot = _snapshot.value
        val allLines = currentSnapshot.scrollback + currentSnapshot.lines
        return getLastCommandOutput(allLines)
    }

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    override fun setAnsiPalette(ansiColors: IntArray): Int {
        require(ansiColors.size >= 16) {
            "ANSI palette must contain 16 colors"
        }
        val result: Int
        synchronized(nativeLock) {
            result = terminalNative.setPaletteColors(ansiColors, 16)
        }
        invalidateDisplay()
        return result
    }

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    override fun setDefaultColors(foreground: Int, background: Int): Int {
        synchronized(damageLock) {
            currentDefaultForeground = Color(foreground)
            currentDefaultBackground = Color(background)
        }
        val result: Int
        synchronized(nativeLock) {
            result = terminalNative.setDefaultColors(foreground, background)
        }
        invalidateDisplay()
        return result
    }

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    override fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    ) {
        require(ansiColors.size >= 16) {
            "Color scheme must provide 16 ANSI colors"
        }

        setAnsiPalette(ansiColors)
        setDefaultColors(defaultForeground, defaultBackground)
    }

    // ================================================================================
    // TerminalCallbacks implementation
    // ================================================================================

    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
        synchronized(damageLock) {
            addDamageRegion(startRow, endRow, startCol, endCol)
            // When synchronized output is active, accumulate damage but don't post.
            if (!syncOutputActive) {
                requestProcessPendingUpdatesLocked()
            }
        }
        return 0
    }

    // Track the last moverect source region so pushScrollbackLine knows
    // whether it was a full-screen or partial scroll region scroll.
    private var lastMoveRectSrc: TermRect? = null

    override fun moverect(dest: TermRect, src: TermRect): Int {
        // Save source rect — pushScrollbackLine uses it to limit segment shifting
        // to lines within the scroll region (avoiding corruption of tmux status bars etc.)
        lastMoveRectSrc = src
        // Treat moverect as damage on the destination
        return damage(dest.startRow, dest.endRow, dest.startCol, dest.endCol)
    }

    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
        synchronized(damageLock) {
            cursorRow = pos.row
            cursorCol = pos.col
            cursorVisible = visible
            cursorMoved = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun setTermProp(prop: Int, value: TerminalProperty): Int {
        synchronized(damageLock) {
            when (value) {
                is TerminalProperty.StringValue -> {
                    // Property 7 is VTERM_PROP_TITLE (from vterm.h line 257)
                    if (prop == 7) {
                        terminalTitle = value.value
                        propertyChanged = true
                    }
                }

                is TerminalProperty.BoolValue -> {
                    when (prop) {
                        // Property 1 is VTERM_PROP_CURSORVISIBLE (from vterm.h line 254)
                        1 -> {
                            cursorVisible = value.value
                            propertyChanged = true
                        }

                        // Property 2 is VTERM_PROP_CURSORBLINK (from vterm.h line 255)
                        2 -> {
                            cursorBlink = value.value
                            propertyChanged = true
                        }
                        // Property 3 is VTERM_PROP_ALTSCREEN (from vterm.h line 256).
                        // Used by the UI to enable alt-scroll-mode emulation
                        // (translate touch drag → cursor up/down keys for less/man/vim).
                        3 -> {
                            _altScreenActive = value.value
                        }
                    }
                }

                is TerminalProperty.IntValue -> {
                    when (prop) {
                        // VTERM_PROP_CURSORSHAPE
                        6 -> {
                            cursorShape = when (value.value) {
                                1 -> CursorShape.BLOCK
                                2 -> CursorShape.UNDERLINE
                                3 -> CursorShape.BAR_LEFT
                                else -> CursorShape.BLOCK
                            }
                            propertyChanged = true
                        }
                        // VTERM_PROP_MOUSE
                        8 -> {
                            _mouseMode = when (value.value) {
                                0 -> MouseMode.NONE
                                1 -> MouseMode.CLICK
                                2 -> MouseMode.DRAG
                                3 -> MouseMode.MOVE
                                else -> MouseMode.NONE
                            }
                            propertyChanged = true
                        }
                    }
                }

                else -> {
                    // Other properties not handled
                }
            }
            if (propertyChanged) {
                requestProcessPendingUpdatesLocked()
            }
        }
        return 0
    }

    override fun bell(): Int {
        // Bell callback - post to handler to avoid blocking native thread
        handler.post {
            onBell?.invoke()
        }
        return 0
    }

    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, continuation: Boolean): Int {
        // Convert ScreenCell array to TerminalLine.
        // Preserve spaces as real content; reflow trims padding only at logical-line ends.
        val line = convertScreenCellsToTerminalLine(cols, cells, continuation)

        synchronized(damageLock) {
            // During resize: capture pushed lines for later scrollback reflow.
            // libvterm pushes lines at OLD column width during its own reflow.
            if (resizing) {
                resizePushedLines.add(line)
                return 0
            }

            scrollback.add(line)
            if (scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
            scrollbackDirty = true

            // Shift semantic segments up within the scroll region only.
            // Lines outside the region (e.g. tmux status bar) keep their segments.
            val moveRect = lastMoveRectSrc
            lastMoveRectSrc = null
            if (currentLines.size > 1) {
                val shiftEnd = if (moveRect != null) {
                    // Partial scroll region: only shift within the region
                    moveRect.endRow.coerceAtMost(currentLines.size)
                } else {
                    // Full-screen scroll
                    currentLines.size
                }
                val newLines = currentLines.toMutableList()
                for (row in 0 until shiftEnd - 1) {
                    newLines[row] = currentLines[row].copy(
                        semanticSegments = currentLines[row + 1].semanticSegments,
                    )
                }
                // Clear segments for the last line in the scroll region
                if (shiftEnd > 0 && shiftEnd <= currentLines.size) {
                    newLines[shiftEnd - 1] = currentLines[shiftEnd - 1].copy(
                        semanticSegments = emptyList(),
                    )
                }
                currentLines = newLines
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun clearScrollback(): Int {
        synchronized(damageLock) {
            scrollback.clear()
            scrollbackDirty = true
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    private fun convertScreenCellsToTerminalLine(
        cols: Int,
        cells: Array<ScreenCell>,
        continuation: Boolean
    ): TerminalLine {
        val rawCells = cells.map { screenCell ->
            TerminalLine.Cell(
                char = screenCell.char,
                combiningChars = screenCell.combiningChars.filter { it != '\u0000' },
                fgColor = Color(screenCell.fgRed, screenCell.fgGreen, screenCell.fgBlue),
                bgColor = Color(screenCell.bgRed, screenCell.bgGreen, screenCell.bgBlue),
                bold = screenCell.bold,
                italic = screenCell.italic,
                underline = screenCell.underline,
                reverse = screenCell.reverse,
                strike = screenCell.strike,
                width = screenCell.width
            )
        }
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        synchronized(damageLock) {
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
        }
        val cellList = normalizePaddingCells(rawCells, currentDefaultFg, currentDefaultBg)
        return TerminalLine(
            row = -1,
            cells = cellList,
            colsAtCapture = cols,
            continuation = continuation
        )
    }

    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
        synchronized(damageLock) {
            val defaultFg = currentDefaultForeground
            val defaultBg = currentDefaultBackground
            val line = if (resizing) {
                if (!resizeAllowPop) return 0
                if (resizeScrollback.isEmpty()) return 0
                resizeScrollback.removeAt(resizeScrollback.size - 1)
            } else {
                if (scrollback.isEmpty()) return 0
                val popped = scrollback.removeAt(scrollback.size - 1)
                scrollbackDirty = true
                popped
            }

            val trimmedCells = if (resizing) {
                trimTrailingNullCells(line.cells)
            } else {
                trimLogicalTrailingPaddingCells(line.cells, defaultFg, defaultBg)
            }

            // Expand cells back to column-indexed positions.
            // Wide chars (width=2) were stored as single Cell objects during push,
            // so we need to place them at the correct column offset and leave
            // the next slot null (JNI bridge handles null as empty cell).
            var colIndex = 0
            for (cell in trimmedCells) {
                if (colIndex >= cols) break

                val fgArgb = cell.fgColor.toArgb()
                val bgArgb = cell.bgColor.toArgb()

                cells[colIndex] = ScreenCell(
                    char = cell.char,
                    combiningChars = cell.combiningChars,
                    fgRed = (fgArgb shr 16) and 0xFF,
                    fgGreen = (fgArgb shr 8) and 0xFF,
                    fgBlue = fgArgb and 0xFF,
                    bgRed = (bgArgb shr 16) and 0xFF,
                    bgGreen = (bgArgb shr 8) and 0xFF,
                    bgBlue = bgArgb and 0xFF,
                    bold = cell.bold,
                    italic = cell.italic,
                    underline = cell.underline,
                    reverse = cell.reverse,
                    strike = cell.strike,
                    width = cell.width,
                )

                colIndex += cell.width
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()

            // Remaining columns stay null — JNI bridge initializes them as empty cells.
            // Return 2 for continuation lines, 1 for non-continuation, 0 for no data.
            return if (line.continuation) 2 else 1
        }
    }

    override fun onKeyboardInput(data: ByteArray): Int {
        // Keyboard output callback - post to handler
        handler.post {
            onKeyboardInput.invoke(data)
        }
        return 0
    }

    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int): Int {
        // Use the native cursor position from libvterm for OSC sequence processing
        val actions = synchronized(damageLock) {
            oscParser.parse(command, payload, cursorRow, cursorCol, cols)
        }

        synchronized(damageLock) {
            for (action in actions) {
                when (action) {
                    is OscParser.Action.AddSegment -> {
                        addSemanticSegment(
                            action.row,
                            action.startCol,
                            action.endCol,
                            action.type,
                            action.metadata,
                            action.promptId,
                        )
                    }

                    is OscParser.Action.SetCursorShape -> {
                        cursorShape = action.shape
                        propertyChanged = true
                        requestProcessPendingUpdatesLocked()
                    }

                    is OscParser.Action.ClipboardCopy -> {
                        // Post clipboard copy to handler thread to avoid blocking native callback
                        handler.post {
                            onClipboardCopy?.invoke(action.data)
                        }
                    }

                    is OscParser.Action.SetProgress -> {
                        // Post progress change to handler thread to avoid blocking native callback
                        handler.post {
                            onProgressChange?.invoke(action.state, action.progress)
                        }
                    }
                    is OscParser.Action.QueryColor -> {
                        // Respond with current color in rgb:RRRR/GGGG/BBBB format
                        val color = when (action.colorType) {
                            OscParser.DynamicColorType.FOREGROUND -> currentDefaultForeground
                            OscParser.DynamicColorType.BACKGROUND -> currentDefaultBackground
                            OscParser.DynamicColorType.CURSOR -> currentDefaultForeground
                        }
                        val oscNum = when (action.colorType) {
                            OscParser.DynamicColorType.FOREGROUND -> 10
                            OscParser.DynamicColorType.BACKGROUND -> 11
                            OscParser.DynamicColorType.CURSOR -> 12
                        }
                        val argb = color.toArgb()
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        val response = "\u001B]$oscNum;rgb:${"%04x".format(r * 257)}/${"%04x".format(g * 257)}/${"%04x".format(b * 257)}\u001B\\"
                        handler.post {
                            onKeyboardInput.invoke(response.toByteArray())
                        }
                    }
                    is OscParser.Action.SetDynamicColor -> {
                        when (action.colorType) {
                            OscParser.DynamicColorType.FOREGROUND -> {
                                currentDefaultForeground = Color(action.red, action.green, action.blue)
                            }
                            OscParser.DynamicColorType.BACKGROUND -> {
                                currentDefaultBackground = Color(action.red, action.green, action.blue)
                            }
                            OscParser.DynamicColorType.CURSOR -> {
                                // Cursor color - store but no dedicated field yet
                            }
                        }
                        propertyChanged = true
                        if (!damagePosted) {
                            handler.post { processPendingUpdates() }
                            damagePosted = true
                        }
                    }
                    is OscParser.Action.InlineImage -> {
                        // Decode and place image via ImageManager
                        handler.post {
                            handleInlineImage(action)
                        }
                    }
                }
            }
        }
        return 1
    }

    override fun onSyncOutputChanged(active: Boolean) {
        synchronized(damageLock) {
            if (active) {
                syncOutputActive = true
                syncOutputHandler.postDelayed(syncOutputTimeout, SYNC_OUTPUT_TIMEOUT_MS)
            } else {
                syncOutputActive = false
                syncOutputHandler.removeCallbacks(syncOutputTimeout)
                // Flush any accumulated damage
                if (pendingDamageRegions.isNotEmpty() || cursorMoved || propertyChanged) {
                    if (!damagePosted) {
                        handler.post { processPendingUpdates() }
                        damagePosted = true
                    }
                }
            }
        }
    }

    private fun flushSyncOutput() {
        synchronized(damageLock) {
            syncOutputActive = false
            if (pendingDamageRegions.isNotEmpty() || cursorMoved || propertyChanged) {
                if (!damagePosted) {
                    handler.post { processPendingUpdates() }
                    damagePosted = true
                }
            }
        }
    }

    override fun onKittyKeyboardChanged(push: Boolean, flags: Int) {
        synchronized(damageLock) {
            if (push) {
                kittyKeyboardStack.add(flags)
            } else {
                if (kittyKeyboardStack.isNotEmpty()) {
                    kittyKeyboardStack.removeAt(kittyKeyboardStack.size - 1)
                }
            }
        }
    }

    override fun onDcsSequence(command: String, data: String, cursorRow: Int, cursorCol: Int) {
        // DCS 'q' is Sixel graphics
        if (command == "q" || command.endsWith("q")) {
            handler.post {
                val bitmap = sixelDecoder.decode(data) ?: return@post
                val charHeight = 16 // Approximate; actual value from renderer
                val charWidth = 8
                val widthCells = (bitmap.width + charWidth - 1) / charWidth
                val heightCells = (bitmap.height + charHeight - 1) / charHeight
                imageManager.placeImage(bitmap, cursorRow, cursorCol, widthCells, heightCells)
                synchronized(damageLock) {
                    propertyChanged = true
                    if (!damagePosted) {
                        handler.post { processPendingUpdates() }
                        damagePosted = true
                    }
                }
            }
        }
    }

    override fun onApcSequence(data: String, cursorRow: Int, cursorCol: Int) {
        // APC '_G' prefix is Kitty graphics protocol
        if (data.startsWith("G")) {
            handler.post {
                val response = kittyGraphics.handleSequence(data.substring(1), cursorRow, cursorCol)
                if (response != null) {
                    // Send response back via PTY
                    val responseBytes = "\u001B_${response.message}\u001B\\".toByteArray()
                    onKeyboardInput.invoke(responseBytes)
                }
                synchronized(damageLock) {
                    propertyChanged = true
                    if (!damagePosted) {
                        handler.post { processPendingUpdates() }
                        damagePosted = true
                    }
                }
            }
        }
    }

    /**
     * Apply a semantic segment immediately to the current line.
     * Segments are applied immediately so they can be properly shifted during scroll.
     */
    private fun handleInlineImage(action: OscParser.Action.InlineImage) {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(action.data, 0, action.data.size)
            ?: return

        // Parse width/height from params (in cells)
        val requestedWidth = action.params["width"]?.toIntOrNull()
        val requestedHeight = action.params["height"]?.toIntOrNull()
        val charWidth = 8  // Approximate
        val charHeight = 16 // Approximate

        val widthCells = requestedWidth ?: ((bitmap.width + charWidth - 1) / charWidth)
        val heightCells = requestedHeight ?: ((bitmap.height + charHeight - 1) / charHeight)

        imageManager.placeImage(bitmap, action.cursorRow, action.cursorCol, widthCells, heightCells)

        synchronized(damageLock) {
            propertyChanged = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
    }

    private fun addSemanticSegment(
        row: Int,
        startCol: Int,
        endCol: Int,
        semanticType: SemanticType,
        metadata: String?,
        promptId: Int,
    ) {
        synchronized(damageLock) {
            // Apply immediately to currentLines so segments are shifted correctly during scroll
            if (row < 0 || row >= currentLines.size) {
                return
            }

            val line = currentLines[row]
            val newSegment = SemanticSegment(
                startCol = startCol,
                endCol = endCol,
                semanticType = semanticType,
                metadata = metadata,
                promptId = promptId,
            )

            val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }
            currentLines = currentLines.toMutableList().apply {
                this[row] = line.copy(semanticSegments = updatedSegments)
            }

            // Mark for update so processPendingUpdates runs
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
    }

    // ================================================================================
    // Internal snapshot building
    // ================================================================================

    /**
     * Process pending updates and emit new snapshot.
     * This runs on the Handler thread, NOT in the JNI callback.
     */
    @VisibleForTesting
    fun processPendingUpdates() {
        // Collect pending changes
        val damageRegions: List<DamageRegion>
        val needsUpdate: Boolean
        synchronized(damageLock) {
            if (resizing) {
                pendingDamageRegions.clear()
                damagePosted = false
                cursorMoved = false
                propertyChanged = false
                return
            }
            damageRegions = pendingDamageRegions.toList()
            pendingDamageRegions.clear()
            damagePosted = false
            needsUpdate = damageRegions.isNotEmpty() || cursorMoved || propertyChanged
            cursorMoved = false
            propertyChanged = false
        }

        if (!needsUpdate) return

        // Update damaged lines (safe to call getCellRun now - not in callback)
        for (region in damageRegions) {
            // Ensure row is within bounds [0, rows)
            val startRow = region.startRow.coerceIn(0, rows - 1)
            val endRow = region.endRow.coerceIn(startRow, rows) // endRow is exclusive
            for (row in startRow until endRow) {
                updateLine(row)
            }
        }

        // Apply pending semantic segments now that text content is available
        val segmentsToApply: List<PendingSemanticSegment>
        synchronized(damageLock) {
            segmentsToApply = pendingSemanticSegments.toList()
            pendingSemanticSegments.clear()
        }

        for (segment in segmentsToApply) {
            applySemanticSegment(segment)
        }

        // Build and emit new snapshot
        val newSnapshot = buildSnapshot()
        _snapshot.value = newSnapshot

        // Emit command line change if different from last reported
        val cmdInfo = newSnapshot.commandLineInfo
        if (cmdInfo != null) {
            if (cmdInfo.text != lastCommandLineText || cmdInfo.cursorOffset != lastCommandLineCursor) {
                lastCommandLineText = cmdInfo.text
                lastCommandLineCursor = cmdInfo.cursorOffset
                onCommandLineChanged?.invoke(cmdInfo.text, cmdInfo.cursorOffset)
            }
        } else if (lastCommandLineText != null) {
            lastCommandLineText = null
            lastCommandLineCursor = -1
            onCommandLineChanged?.invoke("", 0)
        }
    }

    /**
     * Apply a semantic segment to a line.
     * This is called during processPendingUpdates when the actual text is available.
     */
    private fun applySemanticSegment(segment: PendingSemanticSegment) {
        val row = segment.row

        // Ensure row is valid
        if (row < 0 || row >= currentLines.size) {
            return
        }

        val line = currentLines[row]

        // Create new segment
        val newSegment = SemanticSegment(
            startCol = segment.startCol,
            endCol = segment.endCol,
            semanticType = segment.semanticType,
            metadata = segment.metadata,
            promptId = segment.promptId,
        )

        // Add to existing segments (sorted by startCol)
        val updatedSegments = (line.semanticSegments + newSegment)
            .sortedBy { it.startCol }

        // Update the line with new segments
        currentLines = currentLines.toMutableList().apply {
            this[row] = line.copy(semanticSegments = updatedSegments)
        }
    }

    /**
     * Update a single line by fetching cell data from the terminal.
     */
    private fun updateLine(row: Int) {
        if (row !in 0..<rows) {
            return
        }

        val line: TerminalLine
        synchronized(nativeLock) {
            line = readLineFromLibvterm(row)
        }

        synchronized(damageLock) {
            currentLines = currentLines.toMutableList().apply {
                val existingSegments = this[row].semanticSegments
                this[row] = line.copy(semanticSegments = existingSegments)
            }
        }
    }

    private fun readLineFromLibvterm(row: Int): TerminalLine {
        return readLineFromLibvterm(row, preserveTrailingSpaces = true)
    }

    private fun readLineFromLibvterm(
        row: Int,
        preserveTrailingSpaces: Boolean
    ): TerminalLine {
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        synchronized(damageLock) {
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
        }

        val cells = ArrayList<TerminalLine.Cell>(cols)
        var col = 0

        while (col < cols) {
            cellRun.reset()
            val runLength = terminalNative.getCellRun(row, col, cellRun)
            val runCharCount = cellRun.runLength

            if (runLength <= 0) {
                while (col < cols) {
                    cells.add(
                        TerminalLine.Cell(
                            char = '\u0000',
                            fgColor = currentDefaultFg,
                            bgColor = currentDefaultBg,
                        ),
                    )
                    col++
                }
                break
            }

            val fgColor = Color(cellRun.fgRed, cellRun.fgGreen, cellRun.fgBlue)
            val bgColor = Color(cellRun.bgRed, cellRun.bgGreen, cellRun.bgBlue)

            var charIndex = 0
            var cellsInRun = 0

            while (charIndex < runCharCount && cellsInRun < runLength) {
                val char = cellRun.chars[charIndex]
                if (char == 0.toChar()) {
                    // Blank cell (padding). Keep as NUL so we can trim padding later.
                    cells.add(
                        TerminalLine.Cell(
                            char = '\u0000',
                            fgColor = fgColor,
                            bgColor = bgColor,
                            bold = cellRun.bold,
                            italic = cellRun.italic,
                            underline = cellRun.underline,
                            blink = cellRun.blink,
                            reverse = cellRun.reverse,
                            strike = cellRun.strike,
                            width = 1
                        )
                    )
                    charIndex++
                    cellsInRun++
                    continue
                }

                var combiningChars: MutableList<Char>? = null
                charIndex++

                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        combiningChars = mutableListOf(nextChar)
                        charIndex++
                    }
                }

                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    if (combiningChars == null) {
                        combiningChars = mutableListOf()
                    }
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width
                val extraChars = combiningChars ?: TerminalLine.EMPTY_COMBINING_CHARS
                val width = if (extraChars.isNotEmpty() && extraChars[0].isLowSurrogate()) {
                    val codepoint = Character.toCodePoint(char, extraChars[0])
                    if (isFullwidthCodepoint(codepoint)) 2 else 1
                } else {
                    if (isFullwidthCharacter(char)) 2 else 1
                }

                cells.add(
                    TerminalLine.Cell(
                        char = char,
                        combiningChars = extraChars,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        bold = cellRun.bold,
                        italic = cellRun.italic,
                        underline = cellRun.underline,
                        blink = cellRun.blink,
                        reverse = cellRun.reverse,
                        strike = cellRun.strike,
                        width = width,
                    ),
                )

                cellsInRun++
                if (width == 2) {
                    cellsInRun++
                }
            }

            col += cellsInRun
        }

        val continuation = terminalNative.getLineContinuation(row)
        val normalizedCells = if (preserveTrailingSpaces) {
            normalizePaddingCells(cells, currentDefaultFg, currentDefaultBg)
        } else {
            normalizePaddingCellsPreserveTrailingSpaces(cells, currentDefaultFg, currentDefaultBg)
        }
        return TerminalLine(
            row = row,
            cells = normalizedCells,
            colsAtCapture = cols,
            continuation = continuation
        )
    }

    private fun normalizePaddingCellsPreserveTrailingSpaces(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine.Cell> {
        if (cells.isEmpty()) return cells

        var lastNonDefaultSpace = -1
        var idx = cells.size - 1
        while (idx >= 0) {
            val cell = cells[idx]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                lastNonDefaultSpace = idx
                break
            }
            idx--
        }

        if (lastNonDefaultSpace < 0) {
            return cells
        }

        // If the last non-NUL cell is a default-style space, treat it as padding.
        // This preserves real internal spaces but avoids converting trailing padding
        // spaces into visible content on widen/unwrap.
        if (cells[lastNonDefaultSpace].char == ' ' &&
            cells[lastNonDefaultSpace].combiningChars.isEmpty() &&
            isDefaultStyle(cells[lastNonDefaultSpace], defaultFg, defaultBg)
        ) {
            while (lastNonDefaultSpace >= 0) {
                val cell = cells[lastNonDefaultSpace]
                val isPadding = cell.char == '\u0000' ||
                    (cell.char == ' ' && cell.combiningChars.isEmpty() &&
                        isDefaultStyle(cell, defaultFg, defaultBg))
                if (!isPadding) break
                lastNonDefaultSpace--
            }
        }

        return buildList(cells.size) {
            for (i in 0..lastNonDefaultSpace) {
                val cell = cells[i]
                if (isPaddingCell(cell) && !isDefaultStyle(cell, defaultFg, defaultBg)) {
                    add(cell.copy(char = ' '))
                } else {
                    add(cell)
                }
            }
            for (i in lastNonDefaultSpace + 1 until cells.size) {
                val cell = cells[i]
                if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                    add(cell.copy(char = '\u0000', combiningChars = emptyList()))
                } else {
                    add(cell)
                }
            }
        }
    }

    /**
     * Fix continuation groups where libvterm pads old-width lines on widen.
     * Rewrap groups that contain padded intermediate lines so logical lines
     * don't gain internal padding spaces.
     */
    private fun fixPoppedContinuationPadding(
        lines: List<TerminalLine>,
        cols: Int,
        oldCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine> {
        if (cols <= 0 || lines.isEmpty() || oldCols >= cols) return lines

        val result = lines.toMutableList()
        var i = 0
        while (i < result.size) {
            if (!hasOldWidthPadding(result[i], oldCols, cols, defaultFg, defaultBg)) {
                i++
                continue
            }

            var groupEnd = i + 1
            while (groupEnd < result.size &&
                hasOldWidthPadding(result[groupEnd - 1], oldCols, cols, defaultFg, defaultBg)
            ) {
                groupEnd++
            }

            val mergedCells = mutableListOf<TerminalLine.Cell>()
            for (k in i until groupEnd) {
                val lineCells = result[k].cells
                var trimEnd = lineCells.size
                while (trimEnd > 0 && isTrimmablePaddingCell(lineCells[trimEnd - 1], defaultFg, defaultBg)) {
                    trimEnd--
                }
                val effectiveEnd = if (hasOldWidthPadding(result[k], oldCols, cols, defaultFg, defaultBg)) {
                    minOf(trimEnd, oldCols)
                } else {
                    trimEnd
                }
                mergedCells.addAll(lineCells.subList(0, effectiveEnd))
            }

            val rewrapped = wrapLogicalLine(
                cells = mergedCells,
                segments = emptyList(),
                newCols = cols,
                defaultFg = defaultFg,
                defaultBg = defaultBg
            )

            for (k in i until groupEnd) {
                val rowIdx = k - i
                result[k] = if (rowIdx < rewrapped.size) {
                    rewrapped[rowIdx].copy(row = k)
                } else {
                    TerminalLine.empty(k, cols, defaultFg, defaultBg)
                }
            }

            i = groupEnd
        }

        return result
    }

    /**
     * Add HYPERLINK semantic segments for any URLs detected by ImplicitLinkDetector
     * that are not already covered by an explicit OSC 8 hyperlink segment.
     */
    private fun enrichWithImplicitLinks(line: TerminalLine): TerminalLine {
        val implicitLinks = linkDetector.detectLinks(line)
        if (implicitLinks.isEmpty()) return line

        val extra = implicitLinks.mapNotNull { link ->
            // Skip if an OSC 8 segment already covers this range
            val alreadyCovered = line.semanticSegments.any {
                it.semanticType == SemanticType.HYPERLINK &&
                    it.startCol <= link.startCol && it.endCol >= link.endCol
            }
            if (alreadyCovered) return@mapNotNull null

            SemanticSegment(
                startCol = link.startCol,
                endCol = link.endCol + 1, // endCol is exclusive in SemanticSegment
                semanticType = SemanticType.HYPERLINK,
                metadata = link.url,
                promptId = -1
            )
        }
        if (extra.isEmpty()) return line

        return line.copy(
            semanticSegments = (line.semanticSegments + extra).sortedBy { it.startCol }
        )
    }

    /**
     * Build a complete snapshot of terminal state.
     */
    private fun buildSnapshot(): TerminalSnapshot {
        // Read all mutable state under damageLock to ensure cross-thread visibility.
        // addSemanticSegment writes currentLines on the JNI callback thread; without
        // the lock here, the snapshot-building thread might see a stale reference.
        val lines: List<TerminalLine>
        val scrollbackCopy: List<TerminalLine>
        synchronized(damageLock) {
            if (scrollbackDirty) {
                scrollbackSnapshot = scrollback.toList()
                scrollbackDirty = false
            }
            lines = currentLines.toList() // Immutable copy (24 references)
            scrollbackCopy = scrollbackSnapshot // Reuse cached immutable copy
        }

        // Enrich lines with implicit link segments so the renderer can
        // underline detected URLs (http/https/…) that weren't wrapped in OSC 8.
        val enrichedLines = currentLines.map { line -> enrichWithImplicitLinks(line) }

        return TerminalSnapshot(
            lines = enrichedLines,
            scrollback = scrollbackSnapshot,  // Reuse cached immutable copy
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            cursorShape = cursorShape,
            cursorBlink = cursorBlink,
            terminalTitle = terminalTitle,
            rows = rows,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = sequenceNumber++,
            mouseMode = _mouseMode,
            imagePlacements = imageManager.getPlacements(),
            commandLineInfo = extractCommandLineInfo()
        )
    }

    /**
     * Extract the current command line text by reading terminal buffer cells
     * between the OSC 133 prompt end column and the cursor position.
     */
    private fun extractCommandLineInfo(): CommandLineInfo? {
        val row = cursorRow
        if (row < 0 || row >= currentLines.size) return null

        val line = currentLines[row]
        val promptSegment = line.getSegmentsOfType(SemanticType.PROMPT).lastOrNull()
            ?: return null

        val inputStartCol = promptSegment.endCol
        if (inputStartCol < 0 || inputStartCol > cols) return null

        // Read cell text from inputStartCol up to cursorCol only.
        // Text after the cursor may include shell autosuggestions (e.g. zsh-autosuggestions)
        // which are NOT part of the actual command buffer and must be excluded.
        val endCol = cursorCol.coerceAtMost(line.cells.size)
        val fullText = buildString {
            for (i in inputStartCol until endCol) {
                val cell = line.cells[i]
                if (cell.char == '\u0000') {
                    append(' ')
                } else {
                    append(cell.char)
                    cell.combiningChars.forEach { append(it) }
                }
            }
        }.trimEnd()

        val cursorOffset = fullText.length

        return CommandLineInfo(
            text = fullText,
            cursorOffset = cursorOffset,
            promptId = promptSegment.promptId
        )
    }

    // ================================================================================
    // Helper methods
    // ================================================================================

    /**
     * Trigger a full display redraw.
     * Used when global display properties change (colors, etc.).
     */
    private fun invalidateDisplay() {
        synchronized(damageLock) {
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols))
            requestProcessPendingUpdatesLocked()
        }
    }

    /**
     * Schedule snapshot work at display-frame cadence.
     *
     * libvterm can report many small damage/cursor callbacks while a single PTY read is
     * processed. Running updateLine/buildSnapshot for every callback burst can outpace
     * vsync and make Compose redraw the terminal multiple times for one displayed frame.
     *
     * MUST be called with damageLock held.
     */
    private fun requestProcessPendingUpdatesLocked() {
        if (damagePosted) return
        damagePosted = true
        if (looper == Looper.getMainLooper()) {
            handler.post {
                Choreographer.getInstance().postFrameCallback {
                    processPendingUpdates()
                }
            }
        } else {
            handler.post {
                processPendingUpdates()
            }
        }
    }

    private fun reflowLines(
        lines: List<TerminalLine>,
        newCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine> {
        if (lines.isEmpty() || newCols <= 0) {
            return lines
        }

        val result = mutableListOf<TerminalLine>()
        var logicalCells = mutableListOf<TerminalLine.Cell>()
        var logicalSegments = mutableListOf<SemanticSegment>()
        var logicalWidth = 0

        fun flushLogicalLine() {
            if (logicalCells.isEmpty()) {
                return
            }
            val normalizedCells = normalizeLogicalPaddingCells(logicalCells)
            val trimmedCells = trimTrailingNullCells(normalizedCells)
            val trimmedWidth = cellsWidth(trimmedCells)
            val trimmedSegments = if (logicalSegments.isEmpty()) {
                logicalSegments
            } else {
                logicalSegments.mapNotNull { segment ->
                    val start = segment.startCol
                    val end = segment.endCol.coerceAtMost(trimmedWidth)
                    if (start >= trimmedWidth || end <= start) {
                        null
                    } else {
                        segment.copy(endCol = end)
                    }
                }
            }
            val wrapped = wrapLogicalLine(
                cells = trimmedCells,
                segments = trimmedSegments,
                newCols = newCols,
                defaultFg = defaultFg,
                defaultBg = defaultBg
            )
            if (wrapped.isNotEmpty()) {
                wrapped.forEach { line ->
                    val hasContent = line.cells.any { cell ->
                        cell.char != '\u0000' || cell.combiningChars.isNotEmpty()
                    }
                    if (!hasContent) {
                        if (!line.continuation) {
                            result.add(line)
                        }
                    } else {
                        val trimmed = trimTrailingNullCells(line.cells)
                        result.add(
                            if (trimmed.size < line.cells.size) {
                                line.copy(cells = trimmed)
                            } else {
                                line
                            }
                        )
                    }
                }
            }
            logicalCells = mutableListOf()
            logicalSegments = mutableListOf()
            logicalWidth = 0
        }

        for (i in lines.indices) {
            val line = lines[i]

            // Trim trailing blank padding (NUL + default-style spaces) from every
            // physical line so padding doesn't become internal spaces when lines
            // are concatenated.
            val lineCells = trimTrailingNullCells(line.cells)

            val hasContent = lineCells.any { cell ->
                cell.char != '\u0000' || cell.combiningChars.isNotEmpty()
            }
            if (!hasContent) {
                if (!line.continuation) {
                    flushLogicalLine()
                    result.add(blankLine(newCols, defaultFg, defaultBg))
                }
                continue
            }

            val lineWidth = cellsWidth(lineCells)

            // If this line is NOT a continuation, flush the previous logical line first
            if (!line.continuation && logicalCells.isNotEmpty()) {
                flushLogicalLine()
            }

            val offset = logicalWidth
            logicalCells.addAll(lineCells)
            if (line.semanticSegments.isNotEmpty()) {
                for (segment in line.semanticSegments) {
                    logicalSegments.add(
                        segment.copy(
                            startCol = segment.startCol + offset,
                            endCol = segment.endCol + offset
                        )
                    )
                }
            }
            logicalWidth += lineWidth
        }

        flushLogicalLine()

        return result
    }

    private fun wrapLogicalLine(
        cells: List<TerminalLine.Cell>,
        segments: List<SemanticSegment>,
        newCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine> {
        val lines = mutableListOf<TerminalLine>()
        var lineCells = mutableListOf<TerminalLine.Cell>()
        var lineWidth = 0
        var lineStartCol = 0

        fun emitLine() {
            if (lineWidth == 0 && lineCells.isEmpty()) {
                return
            }
            val lineEndCol = lineStartCol + lineWidth
            val lineSegments = if (segments.isEmpty()) {
                emptyList()
            } else {
                segments.filter { it.endCol > lineStartCol && it.startCol < lineEndCol }
                    .map { segment ->
                        val start = if (segment.startCol < lineStartCol) lineStartCol else segment.startCol
                        val end = if (segment.endCol > lineEndCol) lineEndCol else segment.endCol
                        segment.copy(
                            startCol = start - lineStartCol,
                            endCol = end - lineStartCol
                        )
                    }
            }

            val paddedCells = padLineCells(lineCells, lineWidth, newCols, defaultFg, defaultBg)
            lines.add(
                TerminalLine(
                    row = -1,
                    cells = paddedCells,
                    semanticSegments = lineSegments,
                    colsAtCapture = newCols,
                    continuation = lines.isNotEmpty()
                )
            )

            lineStartCol = lineEndCol
            lineCells = mutableListOf()
            lineWidth = 0
        }

        for (cell in cells) {
            val cellWidth = cell.width.coerceAtLeast(1)
            if (lineWidth > 0 && lineWidth + cellWidth > newCols) {
                emitLine()
            }
            lineCells.add(cell)
            lineWidth += cellWidth
            if (lineWidth == newCols) {
                emitLine()
            }
        }

        emitLine()
        return lines
    }

    /**
     * Trim trailing padding cells — only '\0' padding.
     * Used before concatenation and when storing reflowed lines so erased cells
     * don't become internal gaps on subsequent resizes.
     * This ignores cell colors/attributes since padding can carry non-default
     * colors from the terminal's background state.
     */
    private fun trimTrailingPaddingCells(cells: List<TerminalLine.Cell>): List<TerminalLine.Cell> {
        var end = cells.size
        while (end > 0) {
            val cell = cells[end - 1]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                break
            }
            end--
        }
        return if (end == cells.size) {
            cells
        } else {
            cells.subList(0, end)
        }
    }

    private fun trimTrailingNullCells(cells: List<TerminalLine.Cell>): List<TerminalLine.Cell> {
        var end = cells.size
        while (end > 0) {
            val cell = cells[end - 1]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                break
            }
            end--
        }
        return if (end == cells.size) {
            cells
        } else {
            cells.subList(0, end)
        }
    }

    private fun trimLogicalTrailingPaddingCells(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine.Cell> {
        return trimTrailingWhitespaceCells(cells, defaultFg, defaultBg, aggressiveSpaces = false)
    }

    private fun convertTrailingPaddingToSpaces(
        cells: List<TerminalLine.Cell>
    ): List<TerminalLine.Cell> {
        var lastContent = cells.size - 1
        while (lastContent >= 0) {
            val cell = cells[lastContent]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                break
            }
            lastContent--
        }
        if (lastContent == cells.size - 1) return cells
        if (lastContent < 0) return cells

        return buildList(cells.size) {
            for (i in 0..lastContent) {
                add(cells[i])
            }
            for (i in lastContent + 1 until cells.size) {
                val cell = cells[i]
                add(cell.copy(char = ' '))
            }
        }
    }

    private fun normalizeLogicalPaddingCells(
        cells: List<TerminalLine.Cell>
    ): List<TerminalLine.Cell> {
        val lastContent = lastContentIndex(cells)
        if (lastContent < 0) return cells

        return buildList(cells.size) {
            for (i in cells.indices) {
                val cell = cells[i]
                if (i <= lastContent && isPaddingCell(cell)) {
                    add(cell.copy(char = ' '))
                } else {
                    add(cell)
                }
            }
        }
    }


    private fun lastContentIndex(cells: List<TerminalLine.Cell>): Int {
        var idx = cells.size - 1
        while (idx >= 0) {
            val cell = cells[idx]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                return idx
            }
            idx--
        }
        return -1
    }


    /**
     * Normalize padding: treat internal blanks as spaces, keep trailing padding as NUL.
     * This preserves tab-aligned gaps while preventing padding from reflowing into
     * extra lines on resize.
     */
    private fun normalizePaddingCells(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine.Cell> {
        if (cells.isEmpty()) return cells

        var lastContent = cells.size - 1
        while (lastContent >= 0) {
            val cell = cells[lastContent]
            if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                break
            }
            lastContent--
        }

        if (lastContent < 0) {
            return cells
        }

        return buildList {
            for (i in 0..lastContent) {
                val cell = cells[i]
                if (isPaddingCell(cell) && !isDefaultStyle(cell, defaultFg, defaultBg)) {
                    add(cell.copy(char = ' '))
                } else {
                    add(cell)
                }
            }
            for (i in lastContent + 1 until cells.size) {
                val cell = cells[i]
                if (cell.char != '\u0000' || cell.combiningChars.isNotEmpty()) {
                    add(cell.copy(char = '\u0000', combiningChars = emptyList()))
                } else {
                    add(cell)
                }
            }
        }
    }

    private fun isDefaultStyle(
        cell: TerminalLine.Cell,
        defaultFg: Color,
        defaultBg: Color
    ): Boolean {
        return cell.fgColor == defaultFg && cell.bgColor == defaultBg &&
            !cell.bold && !cell.italic && cell.underline == 0 &&
            !cell.blink && !cell.reverse && !cell.strike
    }

    private fun cellsWidth(cells: List<TerminalLine.Cell>): Int {
        var width = 0
        for (cell in cells) {
            width += cell.width.coerceAtLeast(1)
        }
        return width
    }

    private fun isPaddingCell(cell: TerminalLine.Cell): Boolean {
        return cell.char == '\u0000' && cell.combiningChars.isEmpty()
    }

    private fun trimTrailingWhitespaceCells(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color,
        aggressiveSpaces: Boolean
    ): List<TerminalLine.Cell> {
        var end = cells.size
        while (end > 0) {
            val cell = cells[end - 1]
            val isTrimmable = if (aggressiveSpaces) {
                cell.char == '\u0000' || cell.char == ' '
            } else {
                isTrimmablePaddingCell(cell, defaultFg, defaultBg)
            }
            if (!isTrimmable) {
                break
            }
            end--
        }
        return if (end == cells.size) {
            cells
        } else {
            cells.subList(0, end)
        }
    }

    private fun isTrimmablePaddingCell(
        cell: TerminalLine.Cell,
        defaultFg: Color,
        defaultBg: Color
    ): Boolean {
        if (isPaddingCell(cell)) {
            return true
        }
        return cell.char == ' ' && cell.combiningChars.isEmpty() &&
            cell.fgColor == defaultFg && cell.bgColor == defaultBg &&
            !cell.bold && !cell.italic && cell.underline == 0 &&
            !cell.blink && !cell.reverse && !cell.strike
    }

    private fun stripOldWidthPadding(
        lines: List<TerminalLine>,
        oldCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine> {
        if (lines.isEmpty() || oldCols <= 0) return lines

        return lines.map { line ->
            val cells = line.cells
            if (cells.size < oldCols) return@map line

            val hasContentBeforeOldCols = !isTrimmablePaddingCell(cells[oldCols - 1], defaultFg, defaultBg)
            val isPaddedAfterOldCols = oldCols < cells.size &&
                (oldCols until cells.size).all { idx ->
                    isTrimmablePaddingCell(cells[idx], defaultFg, defaultBg)
                }

            if (hasContentBeforeOldCols && isPaddedAfterOldCols) {
                var trimEnd = oldCols
                while (trimEnd > 0 && isTrimmablePaddingCell(cells[trimEnd - 1], defaultFg, defaultBg)) {
                    trimEnd--
                }
                line.copy(cells = cells.subList(0, trimEnd))
            } else {
                line
            }
        }
    }

    private fun hasOldWidthPadding(
        line: TerminalLine,
        oldCols: Int,
        newCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): Boolean {
        if (oldCols <= 0 || newCols <= oldCols) return false
        val cells = line.cells
        if (cells.size < oldCols || cells.size < newCols) return false
        val hasContentBeforeOldCols = !isTrimmablePaddingCell(cells[oldCols - 1], defaultFg, defaultBg)
        val isPaddedAfterOldCols = (oldCols until cells.size).all { idx ->
            isTrimmablePaddingCell(cells[idx], defaultFg, defaultBg)
        }
        return hasContentBeforeOldCols && isPaddedAfterOldCols
    }

    private fun blankLine(newCols: Int, defaultFg: Color, defaultBg: Color): TerminalLine {
        return TerminalLine(
            row = -1,
            cells = List(newCols) { blankCell(defaultFg, defaultBg) },
            semanticSegments = emptyList(),
            colsAtCapture = newCols
        )
    }

    private fun padLineCells(
        cells: List<TerminalLine.Cell>,
        lineWidth: Int,
        newCols: Int,
        defaultFg: Color,
        defaultBg: Color
    ): List<TerminalLine.Cell> {
        if (lineWidth >= newCols) {
            return cells
        }
        val padded = cells.toMutableList()
        val padCount = newCols - lineWidth
        repeat(padCount) {
            padded.add(blankCell(defaultFg, defaultBg))
        }
        return padded
    }

    private fun blankCell(defaultFg: Color, defaultBg: Color): TerminalLine.Cell {
        return TerminalLine.Cell(
            char = '\u0000',
            fgColor = defaultFg,
            bgColor = defaultBg
        )
    }

    /**
     * Add a damage region, coalescing with existing regions where possible.
     *
     * This prevents unbounded growth of damage regions during rapid updates
     * (like cacafire). Regions are coalesced if they overlap or touch on row boundaries.
     *
     * MUST be called with damageLock held.
     */
    private fun addDamageRegion(startRow: Int, endRow: Int, startCol: Int, endCol: Int) {
        if (resizing) {
            return
        }
        // If list is getting large, coalesce more aggressively
        if (pendingDamageRegions.size > 100) {
            // Just mark entire screen as damaged to avoid O(n²) coalescing
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols))
            return
        }

        // Try to merge with existing regions
        var merged = false
        for (i in pendingDamageRegions.indices) {
            val existing = pendingDamageRegions[i]

            // Check if regions overlap or touch on row boundaries
            val rowsOverlap = !(endRow < existing.startRow || startRow > existing.endRow)

            if (rowsOverlap) {
                // Merge the regions
                val newStartRow = minOf(startRow, existing.startRow)
                val newEndRow = maxOf(endRow, existing.endRow)
                val newStartCol = minOf(startCol, existing.startCol)
                val newEndCol = maxOf(endCol, existing.endCol)

                pendingDamageRegions[i] = DamageRegion(newStartRow, newEndRow, newStartCol, newEndCol)
                merged = true
                break
            }
        }

        if (!merged) {
            pendingDamageRegions.add(DamageRegion(startRow, endRow, startCol, endCol))
        }
    }

    private fun isCombiningCharacter(char: Char): Boolean = UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)

    private fun isFullwidthCharacter(char: Char): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(char.code, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
            eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    private fun isFullwidthCodepoint(codepoint: Int): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(codepoint, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
            eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    companion object {
        private const val TAG = "TerminalEmulatorImpl"
    }
}

/**
 * Represents a damaged region that needs updating.
 */
private data class DamageRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int,
)

/**
 * Represents a semantic segment waiting to be applied to a line.
 * Segments are queued during OSC processing and applied during processPendingUpdates
 * when the actual text content is available.
 */
private data class PendingSemanticSegment(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String?,
    val promptId: Int,
)

/**
 * Represents the size of the terminal in characters.
 */
data class TerminalDimensions(
    val rows: Int,
    val columns: Int,
)
