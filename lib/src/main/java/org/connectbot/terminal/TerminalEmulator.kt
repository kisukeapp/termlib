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
    fun dispatchCharacter(modifiers: Int, character: Char)

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
        defaultBackground: Int
    )

    val dimensions: TerminalDimensions
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
            onProgressChange: ((ProgressState, Int) -> Unit)? = null
        ): TerminalEmulator {
            return TerminalEmulatorImpl(
                looper = looper,
                initialRows = initialRows,
                initialCols = initialCols,
                defaultForeground = defaultForeground,
                defaultBackground = defaultBackground,
                onKeyboardInput = onKeyboardInput,
                onBell = onBell,
                onResize = onResize,
                onClipboardCopy = onClipboardCopy,
                onProgressChange = onProgressChange
            )
        }
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
    private val onProgressChange: ((ProgressState, Int) -> Unit)? = null
) : TerminalEmulator, TerminalCallbacks {

    companion object {
        private const val REFLOW_LOG_TAG = "TERMLIB_REFLOW"
    }

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
        TerminalSnapshot.empty(initialRows, initialCols, currentDefaultForeground, currentDefaultBackground)
    )
    internal val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // Sequence number for ordering snapshots
    private var sequenceNumber = 0L

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
    private var resizePushCount: Int = 0

    // Serializes all access to libvterm via terminalNative.
    // libvterm is NOT thread-safe: writeInput() (transport thread) and resize() (main
    // thread) must not call into it concurrently. Lock ordering: nativeLock → damageLock
    // (never the reverse) to prevent deadlocks.
    private val nativeLock = Any()

    // Native terminal instance - MUST be initialized AFTER damageLock and other state
    private val terminalNative by lazy {
        TerminalNative(this).apply {
            resize(initialRows, initialCols)
        }
    }

    // Parser for OSC sequences
    private val oscParser = OscParser()

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
        // Snapshot state and prepare for resize under damageLock.
        val shouldLog = shouldLogReflow()
        val preLogData: ReflowLogData?
        var gapScanScrollback: List<TerminalLine>? = null
        var gapScanScreen: List<TerminalLine>? = null
        val preReflowedScrollback: List<TerminalLine>
        synchronized(damageLock) {
            resizing = true
            resizeAllowPop = !(newCols < oldCols && newRows <= oldRows)
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
            preResizeScrollback = scrollback.toList()
            preResizeScreenLines = currentLines.toList()
            resizePushCount = 0
            pendingDamageRegions.clear()
            damagePosted = false
            preLogData = if (shouldLog) {
                buildReflowLogData(
                    stage = "pre",
                    rows = rows,
                    cols = cols,
                    scrollback = preResizeScrollback,
                    screen = preResizeScreenLines,
                    defaultFg = currentDefaultFg,
                    defaultBg = currentDefaultBg
                )
            } else {
                null
            }
        }

        preReflowedScrollback = if (colsChanged) {
            if (preResizeScrollback.isNotEmpty()) {
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
        } else {
            preResizeScrollback
        }

        synchronized(damageLock) {
            resizeScrollback.clear()
            resizeScrollback.addAll(preReflowedScrollback)
        }

        if (preLogData != null) {
            logReflowData(preLogData)
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

            val postLogData: ReflowLogData?
            synchronized(damageLock) {
                // Reflow scrollback: remaining pre-resize scrollback + lines pushed by
                // libvterm during resize (at old column width).
                val pushCount = resizePushCount.coerceAtMost(preResizeScreenLines.size)
                val pushedFromScreen = if (pushCount > 0) {
                    preResizeScreenLines.take(pushCount)
                } else {
                    emptyList()
                }
                val newScrollback = if (colsChanged) {
                    val reflowedPushed = if (pushedFromScreen.isNotEmpty()) {
                        reflowLines(
                            lines = stripOldWidthPadding(
                                pushedFromScreen,
                                oldCols,
                                currentDefaultFg,
                                currentDefaultBg
                            ),
                            newCols = newCols,
                            defaultFg = currentDefaultFg,
                            defaultBg = currentDefaultBg
                        )
                    } else {
                        pushedFromScreen
                    }
                    resizeScrollback + reflowedPushed
                } else {
                    resizeScrollback + pushedFromScreen
                }

                val alignedScrollback = if (colsChanged) {
                    alignScrollbackToScreen(newScrollback, screenLines) ?: newScrollback
                } else {
                    newScrollback
                }

                val boundedScrollback = if (alignedScrollback.size > maxScrollbackLines) {
                    alignedScrollback.takeLast(maxScrollbackLines)
                } else {
                    alignedScrollback
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

                if (colsChanged) {
                    // Leave screen lines as libvterm produced them.
                }

                // For column changes, keep screen lines as libvterm produced them.

                scrollback.clear()
                scrollback.addAll(boundedScrollback)
                scrollbackDirty = true

                currentLines = screenLines

                resizeScrollback.clear()
                resizePushCount = 0
                pendingDamageRegions.clear()
                damagePosted = false

                resizing = false
                resizeAllowPop = true

                postLogData = if (shouldLog) {
                    buildReflowLogData(
                        stage = "post",
                        rows = rows,
                        cols = cols,
                        scrollback = scrollback,
                        screen = currentLines,
                        defaultFg = currentDefaultFg,
                        defaultBg = currentDefaultBg
                    )
                } else {
                    null
                }

                if (shouldLog) {
                    gapScanScrollback = scrollback.toList()
                    gapScanScreen = currentLines.toList()
                }
            }

            if (postLogData != null) {
                logReflowData(postLogData)
            }

            if (gapScanScrollback != null && gapScanScreen != null) {
                logContinuationGaps(
                    scrollback = gapScanScrollback ?: emptyList(),
                    screen = gapScanScreen ?: emptyList(),
                    defaultFg = currentDefaultFg,
                    defaultBg = currentDefaultBg,
                    tagSuffix = "full"
                )
                logInternalBlankRuns(
                    lines = gapScanScrollback ?: emptyList(),
                    label = "sb",
                    defaultFg = currentDefaultFg,
                    defaultBg = currentDefaultBg
                )
                logInternalBlankRuns(
                    lines = gapScanScreen ?: emptyList(),
                    label = "sc",
                    defaultFg = currentDefaultFg,
                    defaultBg = currentDefaultBg
                )
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
    override fun dispatchCharacter(modifiers: Int, character: Char) {
        synchronized(nativeLock) {
            terminalNative.dispatchCharacter(modifiers, character.code)
        }
    }

    /**
     * Clears the terminal emulator screen.
     */
    override fun clearScreen() = writeInput("\u001B[2J\u001B[H".toByteArray())

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
        defaultBackground: Int
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
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
        return 0
    }

    override fun moverect(dest: TermRect, src: TermRect): Int {
        return damage(dest.startRow, dest.endRow, dest.startCol, dest.endCol)
    }

    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
        synchronized(damageLock) {
            cursorRow = pos.row
            cursorCol = pos.col
            cursorVisible = visible
            cursorMoved = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
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
                    }
                }
                is TerminalProperty.IntValue -> {
                    // Property 6 is VTERM_PROP_CURSORSHAPE (from vterm.h line 260)
                    if (prop == 6) {
                        cursorShape = when (value.value) {
                            1 -> CursorShape.BLOCK       // VTERM_PROP_CURSORSHAPE_BLOCK
                            2 -> CursorShape.UNDERLINE   // VTERM_PROP_CURSORSHAPE_UNDERLINE
                            3 -> CursorShape.BAR_LEFT    // VTERM_PROP_CURSORSHAPE_BAR_LEFT
                            else -> CursorShape.BLOCK
                        }
                        propertyChanged = true
                    }
                }
                else -> {
                    // Other properties not handled
                }
            }
            if (propertyChanged && !damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
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
                resizePushCount++
                return 0
            }

            scrollback.add(line)
            if (scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
            scrollbackDirty = true

            // Shift semantic segments up by 1 row (line N's segments move to line N-1).
            if (currentLines.size > 1) {
                val newLines = currentLines.toMutableList()
                for (row in 0 until currentLines.size - 1) {
                    newLines[row] = currentLines[row].copy(
                        semanticSegments = currentLines[row + 1].semanticSegments
                    )
                }
                newLines[currentLines.size - 1] = currentLines[currentLines.size - 1].copy(
                    semanticSegments = emptyList()
                )
                currentLines = newLines
            }

            propertyChanged = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
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
                    width = cell.width
                )

                colIndex += cell.width
            }

            // Remaining columns stay null — JNI bridge initializes them as empty cells
            // Return 2 for continuation lines, 1 for non-continuation
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

    override fun onOscSequence(command: Int, payload: String, nativeCursorRow: Int, nativeCursorCol: Int): Int {
        // Use the native cursor position from libvterm for OSC sequence processing
        val actions = synchronized(damageLock) {
            oscParser.parse(command, payload, nativeCursorRow, nativeCursorCol, cols)
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
                            action.promptId
                        )
                    }
                    is OscParser.Action.SetCursorShape -> {
                        cursorShape = action.shape
                        propertyChanged = true
                        if (!damagePosted) {
                            handler.post { processPendingUpdates() }
                            damagePosted = true
                        }
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
                }
            }
        }
        return 1
    }

    /**
     * Apply a semantic segment immediately to the current line.
     * Segments are applied immediately so they can be properly shifted during scroll.
     */
    private fun addSemanticSegment(
        row: Int,
        startCol: Int,
        endCol: Int,
        semanticType: SemanticType,
        metadata: String?,
        promptId: Int
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
                promptId = promptId
            )

            val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }
            currentLines = currentLines.toMutableList().apply {
                this[row] = line.copy(semanticSegments = updatedSegments)
            }

            // Mark for update so processPendingUpdates runs
            propertyChanged = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
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
            val endRow = region.endRow.coerceIn(startRow, rows)  // endRow is exclusive
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
            promptId = segment.promptId
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

        val cells = mutableListOf<TerminalLine.Cell>()
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
                            bgColor = currentDefaultBg
                        )
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

                val combiningChars = mutableListOf<Char>()
                charIndex++

                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        combiningChars.add(nextChar)
                        charIndex++
                    }
                }

                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                val width = if (combiningChars.isNotEmpty() && combiningChars[0].isLowSurrogate()) {
                    val codepoint = Character.toCodePoint(char, combiningChars[0])
                    if (isFullwidthCodepoint(codepoint)) 2 else 1
                } else {
                    if (isFullwidthCharacter(char)) 2 else 1
                }

                cells.add(
                    TerminalLine.Cell(
                        char = char,
                        combiningChars = combiningChars,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        bold = cellRun.bold,
                        italic = cellRun.italic,
                        underline = cellRun.underline,
                        blink = cellRun.blink,
                        reverse = cellRun.reverse,
                        strike = cellRun.strike,
                        width = width
                    )
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
     * Build a complete snapshot of terminal state.
     */
    private fun buildSnapshot(): TerminalSnapshot {
        // Only copy scrollback if it changed (avoid copying 10K references every frame!)
        synchronized(damageLock) {
            if (scrollbackDirty) {
                scrollbackSnapshot = scrollback.toList()
                scrollbackDirty = false
            }
        }

        return TerminalSnapshot(
            lines = currentLines.toList(),  // Immutable copy (24 references)
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
            sequenceNumber = sequenceNumber++
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
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
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

    private fun alignScrollbackToScreen(
        reflowedAll: List<TerminalLine>,
        screenLines: List<TerminalLine>
    ): List<TerminalLine>? {
        if (screenLines.isEmpty()) return reflowedAll
        if (reflowedAll.isEmpty()) return emptyList()

        fun trimLeadingEmpty(lines: List<TerminalLine>): List<TerminalLine> {
            var start = 0
            while (start < lines.size) {
                val text = lines[start].text.trimEnd()
                if (text.isNotEmpty()) break
                start++
            }
            return if (start == 0) lines else lines.subList(start, lines.size)
        }

        fun matchSuffix(
            reflowed: List<TerminalLine>,
            screen: List<TerminalLine>,
            includeContinuation: Boolean
        ): Int? {
            if (screen.isEmpty()) return reflowed.size
            val maxStart = reflowed.size - screen.size
            if (maxStart < 0) return null

            for (start in maxStart downTo 0) {
                var matched = true
                for (i in screen.indices) {
                    val a = reflowed[start + i]
                    val b = screen[i]
                    val aText = a.text.trimEnd()
                    val bText = b.text.trimEnd()
                    if (aText != bText) {
                        matched = false
                        break
                    }
                    if (includeContinuation && a.continuation != b.continuation) {
                        matched = false
                        break
                    }
                }
                if (matched) return start
            }
            return null
        }

        fun adjustSplit(start: Int, reflowed: List<TerminalLine>): Int {
            var adjusted = start
            while (adjusted > 0 && reflowed[adjusted].continuation) {
                adjusted--
            }
            return adjusted
        }

        val trimmedScreen = trimLeadingEmpty(screenLines)
        val exactStart = matchSuffix(reflowedAll, trimmedScreen, includeContinuation = true)
        if (exactStart != null) {
            val adjusted = adjustSplit(exactStart, reflowedAll)
            return reflowedAll.subList(0, adjusted)
        }

        val looseStart = matchSuffix(reflowedAll, trimmedScreen, includeContinuation = false)
        return if (looseStart != null) {
            val adjusted = adjustSplit(looseStart, reflowedAll)
            reflowedAll.subList(0, adjusted)
        } else {
            null
        }
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
                    continuation = lines.isNotEmpty(),
                    synthetic = true
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

    private fun shouldLogReflow(): Boolean {
        return Log.isLoggable(REFLOW_LOG_TAG, Log.DEBUG)
    }

    private data class ReflowLogData(
        val stage: String,
        val rows: Int,
        val cols: Int,
        val scrollbackTail: List<TerminalLine>,
        val screenHead: List<TerminalLine>,
        val defaultFg: Color,
        val defaultBg: Color
    )

    private fun buildReflowLogData(
        stage: String,
        rows: Int,
        cols: Int,
        scrollback: List<TerminalLine>,
        screen: List<TerminalLine>,
        defaultFg: Color,
        defaultBg: Color
    ): ReflowLogData {
        val tail = if (scrollback.size > 3) scrollback.takeLast(3) else scrollback
        val head = if (screen.size > 3) screen.take(3) else screen
        return ReflowLogData(
            stage = stage,
            rows = rows,
            cols = cols,
            scrollbackTail = tail,
            screenHead = head,
            defaultFg = defaultFg,
            defaultBg = defaultBg
        )
    }

    private fun logReflowData(data: ReflowLogData) {
        Log.d(
            REFLOW_LOG_TAG,
            "reflow ${data.stage}: rows=${data.rows} cols=${data.cols} " +
                "scrollbackTail=${data.scrollbackTail.size} screenHead=${data.screenHead.size}"
        )

        data.scrollbackTail.forEachIndexed { idx, line ->
            Log.d(REFLOW_LOG_TAG, formatLineDebug("sb", idx, line, data.defaultFg, data.defaultBg))
        }
        data.screenHead.forEachIndexed { idx, line ->
            Log.d(REFLOW_LOG_TAG, formatLineDebug("sc", idx, line, data.defaultFg, data.defaultBg))
        }

        if (data.stage == "post") {
            logContinuationGaps(
                scrollback = data.scrollbackTail,
                screen = data.screenHead,
                defaultFg = data.defaultFg,
                defaultBg = data.defaultBg,
                tagSuffix = "head-tail"
            )
        }
    }

    private fun formatLineDebug(
        prefix: String,
        index: Int,
        line: TerminalLine,
        defaultFg: Color,
        defaultBg: Color
    ): String {
        val raw = buildString {
            for (cell in line.cells) {
                append(debugChar(cell))
            }
        }
        val suspicious = hasInternalPadding(line.cells, defaultFg, defaultBg)
        return "$prefix[$index] cont=${line.continuation} colsAt=${line.colsAtCapture} " +
            "suspicious=$suspicious raw=$raw"
    }

    private fun debugChar(cell: TerminalLine.Cell): String {
        return when (cell.char) {
            '\u0000' -> "\\0"
            ' ' -> "\\s"
            '\t' -> "\\t"
            '\r' -> "\\r"
            '\n' -> "\\n"
            else -> cell.char.toString()
        }
    }

    private fun hasInternalPadding(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): Boolean {
        var seenContent = false
        var seenPaddingAfterContent = false
        for (cell in cells) {
            val isPadding = isTrimmablePaddingCell(cell, defaultFg, defaultBg)
            if (isPadding) {
                if (seenContent) {
                    seenPaddingAfterContent = true
                }
            } else {
                if (seenPaddingAfterContent) {
                    return true
                }
                seenContent = true
            }
        }
        return false
    }

    private fun logContinuationGaps(
        scrollback: List<TerminalLine>,
        screen: List<TerminalLine>,
        defaultFg: Color,
        defaultBg: Color,
        tagSuffix: String
    ) {
        var logged = 0
        val maxLogs = 6

        fun logGap(tag: String, prev: TerminalLine, next: TerminalLine) {
            if (logged >= maxLogs) return
            val prevTrail = countTrailingBlanks(prev.cells, defaultFg, defaultBg)
            val nextLead = countLeadingBlanks(next.cells, defaultFg, defaultBg)
            if (prevTrail.total == 0 && nextLead.total == 0) return

            Log.d(
                REFLOW_LOG_TAG,
                "gap $tag/$tagSuffix: " +
                    "prevTrail=${formatBlankCounts(prevTrail)} " +
                    "nextLead=${formatBlankCounts(nextLead)} " +
                    "prevTail='${tailSnippet(prev.cells)}' nextHead='${headSnippet(next.cells)}'"
            )
            logged++
        }

        if (screen.isNotEmpty() && scrollback.isNotEmpty()) {
            val firstScreen = screen.first()
            val prevFromScrollback = scrollback.last()
            if (firstScreen.continuation) {
                logGap("boundary", prevFromScrollback, firstScreen)
            }
        }

        for (i in 1 until scrollback.size) {
            if (logged >= maxLogs) break
            val line = scrollback[i]
            if (!line.continuation) continue
            val prev = scrollback[i - 1]
            logGap("sb[$i]", prev, line)
        }

        for (i in 1 until screen.size) {
            if (logged >= maxLogs) break
            val line = screen[i]
            if (!line.continuation) continue
            val prev = screen[i - 1]
            logGap("sc[$i]", prev, line)
        }
    }

    private data class BlankCounts(
        val total: Int,
        val nul: Int,
        val spaceDefault: Int,
        val spaceStyled: Int
    ) {
        val space: Int = spaceDefault + spaceStyled
    }

    private fun formatBlankCounts(counts: BlankCounts): String {
        return "${counts.total} (nul=${counts.nul}, sp=${counts.space}, " +
            "spDef=${counts.spaceDefault}, spSty=${counts.spaceStyled})"
    }

    private fun countTrailingBlanks(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): BlankCounts {
        var total = 0
        var nul = 0
        var spaceDefault = 0
        var spaceStyled = 0
        var idx = cells.size - 1
        while (idx >= 0) {
            val cell = cells[idx]
            val isNul = cell.char == '\u0000'
            val isSpace = cell.char == ' '
            if (!isNul && !isSpace) break
            total++
            if (isNul) {
                nul++
            } else if (isDefaultStyle(cell, defaultFg, defaultBg)) {
                spaceDefault++
            } else {
                spaceStyled++
            }
            idx--
        }
        return BlankCounts(total, nul, spaceDefault, spaceStyled)
    }

    private fun countLeadingBlanks(
        cells: List<TerminalLine.Cell>,
        defaultFg: Color,
        defaultBg: Color
    ): BlankCounts {
        var total = 0
        var nul = 0
        var spaceDefault = 0
        var spaceStyled = 0
        var idx = 0
        while (idx < cells.size) {
            val cell = cells[idx]
            val isNul = cell.char == '\u0000'
            val isSpace = cell.char == ' '
            if (!isNul && !isSpace) break
            total++
            if (isNul) {
                nul++
            } else if (isDefaultStyle(cell, defaultFg, defaultBg)) {
                spaceDefault++
            } else {
                spaceStyled++
            }
            idx++
        }
        return BlankCounts(total, nul, spaceDefault, spaceStyled)
    }

    private fun tailSnippet(cells: List<TerminalLine.Cell>, max: Int = 12): String {
        val sb = StringBuilder()
        var count = 0
        var idx = cells.size - 1
        while (idx >= 0 && count < max) {
            sb.append(debugChar(cells[idx]))
            count++
            idx--
        }
        return sb.reverse().toString()
    }

    private fun headSnippet(cells: List<TerminalLine.Cell>, max: Int = 12): String {
        val sb = StringBuilder()
        var count = 0
        var idx = 0
        while (idx < cells.size && count < max) {
            sb.append(debugChar(cells[idx]))
            count++
            idx++
        }
        return sb.toString()
    }

    private fun logInternalBlankRuns(
        lines: List<TerminalLine>,
        label: String,
        defaultFg: Color,
        defaultBg: Color
    ) {
        var logged = 0
        val maxLogs = 6

        fun isBlank(cell: TerminalLine.Cell): Boolean {
            return cell.char == '\u0000' || cell.char == ' '
        }

        for (lineIndex in lines.indices) {
            if (logged >= maxLogs) break
            val line = lines[lineIndex]
            val cells = line.cells
            var i = 0
            while (i < cells.size) {
                if (!isBlank(cells[i])) {
                    i++
                    continue
                }
                val runStart = i
                var nul = 0
                var spaceDefault = 0
                var spaceStyled = 0
                while (i < cells.size && isBlank(cells[i])) {
                    val cell = cells[i]
                    if (cell.char == '\u0000') {
                        nul++
                    } else if (isDefaultStyle(cell, defaultFg, defaultBg)) {
                        spaceDefault++
                    } else {
                        spaceStyled++
                    }
                    i++
                }
                val runEnd = i
                val hasLeftContent = runStart > 0
                val hasRightContent = runEnd < cells.size
                val hasStyledGap = spaceStyled > 0
                val hasNulGap = nul > 0
                if (hasLeftContent && hasRightContent && (hasStyledGap || hasNulGap)) {
                    Log.d(
                        REFLOW_LOG_TAG,
                        "internal-gap $label[$lineIndex]: " +
                            "runLen=${runEnd - runStart} nul=$nul " +
                            "spDef=$spaceDefault spSty=$spaceStyled " +
                            "cont=${line.continuation} colsAt=${line.colsAtCapture} " +
                            "ctx='${gapSnippet(cells, runStart, runEnd)}'"
                    )
                    logged++
                    if (logged >= maxLogs) break
                }
            }
        }
    }

    private fun gapSnippet(
        cells: List<TerminalLine.Cell>,
        start: Int,
        end: Int,
        context: Int = 6
    ): String {
        val prefixStart = (start - context).coerceAtLeast(0)
        val suffixEnd = (end + context).coerceAtMost(cells.size)
        val sb = StringBuilder()
        for (i in prefixStart until start) {
            sb.append(debugChar(cells[i]))
        }
        sb.append('[')
        for (i in start until end) {
            sb.append(debugChar(cells[i]))
        }
        sb.append(']')
        for (i in end until suffixEnd) {
            sb.append(debugChar(cells[i]))
        }
        return sb.toString()
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
            colsAtCapture = newCols,
            synthetic = true
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

    private fun isCombiningCharacter(char: Char): Boolean {
        return UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)
    }

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
}

/**
 * Represents a damaged region that needs updating.
 */
private data class DamageRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int
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
    val promptId: Int
)

/**
 * Represents the size of the terminal in characters.
 */
data class TerminalDimensions(
    val rows: Int,
    val columns: Int
)
