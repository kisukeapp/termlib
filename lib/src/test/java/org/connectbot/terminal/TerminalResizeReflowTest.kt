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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalResizeReflowTest {

    private fun createEmulator(rows: Int, cols: Int): TerminalEmulatorImpl {
        return TerminalEmulatorFactory.create(
            initialRows = rows,
            initialCols = cols
        ) as TerminalEmulatorImpl
    }

    private fun TerminalEmulatorImpl.writeAndSync(text: String) {
        writeInput(text.toByteArray())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        processPendingUpdates()
    }

    private fun TerminalEmulatorImpl.resizeAndSync(rows: Int, cols: Int) {
        resize(rows, cols)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        processPendingUpdates()
    }

    private fun TerminalEmulatorImpl.getSnapshot(): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        processPendingUpdates()
        return snapshot.value
    }

    /**
     * Reconstruct logical lines from a list of terminal lines using continuation flags.
     * Each logical line is the concatenation of a non-continuation line and its
     * subsequent continuation lines, with trailing whitespace trimmed.
     */
    private fun extractLogicalLines(lines: List<TerminalLine>): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inLogical = false

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.continuation) {
                if (inLogical) {
                    result.add(current.toString().trimEnd())
                    current = StringBuilder()
                }
                inLogical = true
            }
            // Only trim trailing whitespace on the last line of a logical group;
            // intermediate lines may have meaningful trailing spaces at wrap boundaries.
            val isLastInGroup = i + 1 >= lines.size || !lines[i + 1].continuation
            if (isLastInGroup) {
                current.append(line.text.trimEnd())
            } else {
                current.append(line.text)
            }
        }
        if (inLogical) {
            result.add(current.toString().trimEnd())
        }
        return result.filter { it.isNotEmpty() }
    }

    /**
     * Reconstruct logical lines as raw cell lists using continuation flags.
     * This preserves '\u0000' cells so tests can detect internal NUL corruption.
     */
    private fun extractLogicalCellLines(lines: List<TerminalLine>): List<List<TerminalLine.Cell>> {
        val result = mutableListOf<MutableList<TerminalLine.Cell>>()
        var current: MutableList<TerminalLine.Cell>? = null

        for (line in lines) {
            if (!line.continuation || current == null) {
                current?.let { result.add(it) }
                current = mutableListOf()
            }
            current?.addAll(line.cells)
        }
        current?.let { result.add(it) }
        return result
    }

    private fun trimTrailingPaddingCellsForTest(
        cells: List<TerminalLine.Cell>
    ): List<TerminalLine.Cell> {
        var end = cells.size
        while (end > 0) {
            val cell = cells[end - 1]
            if (cell.char != '\u0000') break
            end--
        }
        return if (end == cells.size) cells else cells.subList(0, end)
    }

    private fun cellsToRawString(cells: List<TerminalLine.Cell>): String {
        return buildString {
            cells.forEach { cell ->
                append(cell.char)
                cell.combiningChars.forEach { append(it) }
            }
        }
    }


    private fun hasInternalNul(cells: List<TerminalLine.Cell>): Boolean {
        var seenContent = false
        var seenNulAfterContent = false
        for (cell in cells) {
            if (cell.char == '\u0000') {
                if (seenContent) seenNulAfterContent = true
            } else {
                if (seenNulAfterContent) return true
                seenContent = true
            }
        }
        return false
    }

    private fun hasTrailingSpaces(cells: List<TerminalLine.Cell>): Boolean {
        var end = cells.size - 1
        while (end >= 0) {
            val cell = cells[end]
            if (cell.char == '\u0000') {
                end--
                continue
            }
            return cell.char == ' '
        }
        return false
    }

    private fun expandTabs(text: String, tabWidth: Int = 8): String {
        val out = StringBuilder()
        var col = 0
        for (ch in text) {
            if (ch == '\t') {
                val spaces = tabWidth - (col % tabWidth)
                repeat(spaces) {
                    out.append(' ')
                    col++
                }
            } else {
                out.append(ch)
                col++
            }
        }
        return out.toString()
    }

    /**
     * Get all logical lines from both scrollback and screen.
     */
    private fun TerminalSnapshot.allLogicalLines(): List<String> {
        return extractLogicalLines(scrollback + lines)
    }

    /**
     * Get all non-empty lines from both scrollback and screen (ignoring continuation).
     */
    private fun TerminalSnapshot.allNonEmptyLines(): List<String> {
        return (scrollback + lines).map { it.text.trimEnd() }.filter { it.isNotEmpty() }
    }

    // ================================================================================
    // Test 1: Wrapping text when resizing narrower
    // ================================================================================

    @Test
    fun testResizeNarrowerWrapsText() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        // Write a 15-char string that fits in 20 cols
        emulator.writeAndSync("ABCDEFGHIJKLMNO")
        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()
        assertEquals(1, beforeLogical.size)
        assertEquals("ABCDEFGHIJKLMNO", beforeLogical[0])

        // Resize to 10 cols — should wrap into 2 physical lines
        emulator.resizeAndSync(10, 10)
        val after = emulator.getSnapshot()

        val afterNonEmpty = after.allNonEmptyLines()
        assertEquals("Expected 2 non-empty lines after narrowing", 2, afterNonEmpty.size)
        assertEquals("ABCDEFGHIJ", afterNonEmpty[0])
        assertEquals("KLMNO", afterNonEmpty[1])

        // Should still be 1 logical line
        val afterLogical = after.allLogicalLines()
        assertEquals(1, afterLogical.size)
        assertEquals("ABCDEFGHIJKLMNO", afterLogical[0])
    }

    @Test
    fun testResizeNarrowerSetsLineContinuation() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        emulator.writeAndSync("ABCDEFGHIJKLMNO")

        emulator.resizeAndSync(10, 10)
        val snapshot = emulator.getSnapshot()

        // Find the wrapped lines in scrollback + screen
        val allLines = snapshot.scrollback + snapshot.lines
        val abcLine = allLines.indexOfFirst { it.text.trimEnd() == "ABCDEFGHIJ" }
        assertTrue("Should find ABCDEFGHIJ line", abcLine >= 0)
        assertFalse(
            "First wrapped line (ABCDEFGHIJ) should not be continuation",
            allLines[abcLine].continuation
        )

        val klmnoLine = allLines.indexOfFirst { it.text.trimEnd() == "KLMNO" }
        assertTrue("Should find KLMNO line", klmnoLine >= 0)
        assertTrue(
            "Second wrapped line (KLMNO) should be continuation",
            allLines[klmnoLine].continuation
        )
    }

    // ================================================================================
    // Test 2: Unwrapping text when resizing wider
    // ================================================================================

    @Test
    fun testResizeWiderUnwrapsText() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        // Write text that wraps at 10 cols
        emulator.writeAndSync("ABCDEFGHIJKLMNO")
        val before = emulator.getSnapshot()

        val beforeNonEmpty = before.allNonEmptyLines()
        assertEquals("Text should wrap to 2 lines at 10 cols", 2, beforeNonEmpty.size)

        // Resize wider — should merge back into 1 logical line
        emulator.resizeAndSync(10, 20)
        val after = emulator.getSnapshot()

        val afterLogical = after.allLogicalLines()
        assertEquals("Should merge to 1 logical line at 20 cols", 1, afterLogical.size)
        assertEquals("ABCDEFGHIJKLMNO", afterLogical[0])
    }

    @Test
    fun testResizeWiderClearsContinuation() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        emulator.writeAndSync("ABCDEFGHIJKLMNO")

        // Resize wider to unwrap
        emulator.resizeAndSync(10, 20)
        val snapshot = emulator.getSnapshot()

        // Find the merged line
        val allLines = snapshot.scrollback + snapshot.lines
        val mergedLine = allLines.firstOrNull { it.text.trimEnd() == "ABCDEFGHIJKLMNO" }
        assertTrue("Should find merged line", mergedLine != null)
        assertFalse("Merged line should not be continuation", mergedLine!!.continuation)
    }

    // ================================================================================
    // Test 3: Round-trip resize (narrow then wide)
    // ================================================================================

    @Test
    fun testRoundTripResizePreservesText() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        val originalText = "Hello World Test!"
        emulator.writeAndSync(originalText)

        // Resize narrow
        emulator.resizeAndSync(10, 10)
        val narrow = emulator.getSnapshot()
        val narrowNonEmpty = narrow.allNonEmptyLines()
        assertEquals("Should wrap to 2 lines", 2, narrowNonEmpty.size)

        // Resize back to original width
        emulator.resizeAndSync(10, 20)
        val restored = emulator.getSnapshot()
        val restoredLogical = restored.allLogicalLines()
        assertTrue(
            "Text should be restored after round-trip",
            restoredLogical.any { it == originalText }
        )
    }

    @Test
    fun testMultipleRoundTripsPreserveText() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        val originalText = "ABCDEFGHIJKLMNOPQRST"  // exactly 20 chars
        emulator.writeAndSync(originalText)

        // Resize narrow → wide → narrow → wide
        emulator.resizeAndSync(10, 10)
        emulator.resizeAndSync(10, 20)
        emulator.resizeAndSync(10, 5)
        emulator.resizeAndSync(10, 20)

        val after = emulator.getSnapshot()
        val logical = after.allLogicalLines()
        assertTrue(
            "Text should survive multiple round-trips",
            logical.any { it == originalText }
        )
    }

    // ================================================================================
    // Test 4: Scrollback lines during resize
    // ================================================================================

    @Test
    fun testScrollbackPreservedDuringResize() = runBlocking {
        val emulator = createEmulator(rows = 3, cols = 20)

        // Write 5 lines to push some into scrollback
        emulator.writeAndSync("Line1\r\n")
        emulator.writeAndSync("Line2\r\n")
        emulator.writeAndSync("Line3\r\n")
        emulator.writeAndSync("Line4\r\n")
        emulator.writeAndSync("Line5\r\n")

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Resize wider
        emulator.resizeAndSync(3, 40)
        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // All logical lines should be preserved
        for (line in beforeLogical) {
            assertTrue(
                "Logical line '$line' should be preserved after resize",
                afterLogical.contains(line)
            )
        }
    }

    // ================================================================================
    // Test 5: Scrollback + screen boundary
    // ================================================================================

    @Test
    fun testResizeAcrossScrollbackScreenBoundary() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 10)

        // Write a long line that wraps and more lines to push part to scrollback
        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRST")  // wraps at 10 into 2 lines
        emulator.writeAndSync("\r\nShort\r\nNext\r\nMore\r\n")

        // Resize wider to merge the wrapped line
        emulator.resizeAndSync(5, 20)
        val snapshot = emulator.getSnapshot()

        val logical = snapshot.allLogicalLines()
        assertTrue(
            "Should contain the merged long line 'ABCDEFGHIJKLMNOPQRST'",
            logical.any { it == "ABCDEFGHIJKLMNOPQRST" }
        )
    }

    // ================================================================================
    // Test 5b: Row increase should reflow popped scrollback lines to new width
    // ================================================================================ 

    @Test
    fun testResizeWiderReflowsPoppedScrollbackLines() = runBlocking {
        val emulator = createEmulator(rows = 3, cols = 10)

        val longLine = "ABCDEFGHIJ1234567890" // 20 chars, wraps into 2 lines at 10 cols

        // Fill screen and push first segment into scrollback
        emulator.writeAndSync(longLine)
        emulator.writeAndSync("\r\nSHORT")
        emulator.writeAndSync("\r\nTAIL") // causes scroll; longLine's first segment moves to scrollback

        val before = emulator.getSnapshot()
        assertTrue("Expected scrollback to have content before resize", before.scrollback.isNotEmpty())

        // Resize wider AND taller so scrollback lines pop into screen
        emulator.resizeAndSync(5, 20)
        val after = emulator.getSnapshot()
        val logical = after.allLogicalLines()

        assertTrue(
            "Popped scrollback line should unwrap without gaps",
            logical.contains(longLine)
        )
    }

    // ================================================================================
    // Test 6: Multiple separate lines (non-continuation)
    // ================================================================================

    @Test
    fun testMultipleNonContinuationLinesPreserved() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        emulator.writeAndSync("Line1\r\n")
        emulator.writeAndSync("Line2\r\n")
        emulator.writeAndSync("Line3\r\n")

        // Resize narrower then back
        emulator.resizeAndSync(10, 10)
        emulator.resizeAndSync(10, 20)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        assertTrue("Should contain Line1", logical.contains("Line1"))
        assertTrue("Should contain Line2", logical.contains("Line2"))
        assertTrue("Should contain Line3", logical.contains("Line3"))
    }

    // ================================================================================
    // Test 7: Empty lines are preserved
    // ================================================================================

    @Test
    fun testEmptyLinesBetweenContentPreserved() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        emulator.writeAndSync("AAA\r\n")
        emulator.writeAndSync("\r\n")  // empty line
        emulator.writeAndSync("BBB\r\n")

        val before = emulator.getSnapshot()
        val beforeScreen = before.lines.map { it.text.trimEnd() }
        assertEquals("AAA", beforeScreen[0])
        assertEquals("", beforeScreen[1])
        assertEquals("BBB", beforeScreen[2])

        // Resize wider and verify structure
        emulator.resizeAndSync(10, 40)
        val after = emulator.getSnapshot()
        val logical = after.allLogicalLines()

        assertTrue("Should contain AAA", logical.contains("AAA"))
        assertTrue("Should contain BBB", logical.contains("BBB"))
    }

    // ================================================================================
    // Test 8: Exact column boundary wrap
    // ================================================================================

    @Test
    fun testExactColumnBoundaryWrap() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        // Write exactly 20 chars — fills 2 rows exactly
        emulator.writeAndSync("1234567890ABCDEFGHIJ")
        val before = emulator.getSnapshot()

        val beforeNonEmpty = before.allNonEmptyLines()
        assertEquals(2, beforeNonEmpty.size)
        assertEquals("1234567890", beforeNonEmpty[0])
        assertEquals("ABCDEFGHIJ", beforeNonEmpty[1])

        // Resize to 20 — should merge into one line
        emulator.resizeAndSync(10, 20)
        val merged = emulator.getSnapshot()
        val mergedLogical = merged.allLogicalLines()
        assertTrue(
            "Should contain merged line",
            mergedLogical.any { it == "1234567890ABCDEFGHIJ" }
        )
    }

    // ================================================================================
    // Test 9: Resize with only row change (no column change)
    // ================================================================================

    @Test
    fun testResizeRowsOnlyNoReflow() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 20)

        emulator.writeAndSync("Hello World")

        // Verify content is there before resize
        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()
        assertTrue(
            "Should contain Hello World before resize",
            beforeLogical.any { it == "Hello World" }
        )

        // Only change rows, not cols — content should be preserved (no reflow)
        emulator.resizeAndSync(10, 20)
        val snapshot = emulator.getSnapshot()

        assertEquals(10, snapshot.rows)
        assertEquals(20, snapshot.cols)

        val logical = snapshot.allLogicalLines()
        assertTrue(
            "Should contain Hello World after row-only resize",
            logical.any { it == "Hello World" }
        )
    }

    // ================================================================================
    // Test 10: Long text wrapping across many lines
    // ================================================================================

    @Test
    fun testLongTextWrapsCorrectly() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        // Write 35 chars — should wrap to 4 lines (10+10+10+5)
        val longText = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"
        emulator.writeAndSync(longText)

        val snapshot = emulator.getSnapshot()
        val nonEmpty = snapshot.allNonEmptyLines()
        assertEquals("Should wrap to 4 lines", 4, nonEmpty.size)
        assertEquals("ABCDEFGHIJ", nonEmpty[0])
        assertEquals("KLMNOPQRST", nonEmpty[1])
        assertEquals("UVWXYZ1234", nonEmpty[2])
        assertEquals("56789", nonEmpty[3])

        // Verify it's 1 logical line
        val logical = snapshot.allLogicalLines()
        assertEquals(1, logical.size)
        assertEquals(longText, logical[0])

        // Resize wider to unwrap
        emulator.resizeAndSync(10, 40)
        val wide = emulator.getSnapshot()
        val wideLogical = wide.allLogicalLines()
        assertTrue(
            "Long text should survive resize",
            wideLogical.any { it == longText }
        )
    }

    // ================================================================================
    // Test 11: Continuation flags survive resize round-trip
    // ================================================================================

    @Test
    fun testContinuationFlagsSurviveRoundTrip() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        // Two separate logical lines
        emulator.writeAndSync("AAAAA\r\n")
        emulator.writeAndSync("BBBBB\r\n")

        // Resize narrow → creates continuation lines
        emulator.resizeAndSync(10, 3)

        // Resize back to wide
        emulator.resizeAndSync(10, 20)
        val wide = emulator.getSnapshot()

        val logical = wide.allLogicalLines()
        assertTrue("Should contain AAAAA", logical.contains("AAAAA"))
        assertTrue("Should contain BBBBB", logical.contains("BBBBB"))

        // These should be separate logical lines (not merged)
        val allLines = wide.scrollback + wide.lines
        val aLine = allLines.firstOrNull { it.text.trimEnd() == "AAAAA" }
        val bLine = allLines.firstOrNull { it.text.trimEnd() == "BBBBB" }
        if (aLine != null) assertFalse("AAAAA should not be continuation", aLine.continuation)
        if (bLine != null) assertFalse("BBBBB should not be continuation", bLine.continuation)
    }

    // ================================================================================
    // Test 12: CJK wide characters
    // ================================================================================

    @Test
    fun testCjkCharsAtWrapBoundary() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        // Write CJK chars that take 2 cols each
        // 世界你好 = 4 chars, 8 cols. Add A (1 col) = 9 cols. Add 测 (2 cols) = 11 cols → wraps
        val text = "\u4e16\u754c\u4f60\u597dA\u6d4b"
        emulator.writeAndSync(text)

        val snapshot = emulator.getSnapshot()
        val nonEmpty = snapshot.allNonEmptyLines()
        assertTrue("CJK text should produce at least 1 line", nonEmpty.isNotEmpty())

        // The total content should be preserved
        val allContent = nonEmpty.joinToString("")
        assertTrue(
            "All CJK characters should be preserved",
            allContent.contains("\u4e16") && allContent.contains("\u6d4b")
        )
    }

    @Test
    fun testCjkRoundTripResize() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        val cjkText = "\u4e16\u754c\u4f60\u597d"  // 世界你好 (4 chars, 8 cols)
        emulator.writeAndSync(cjkText)

        val before = emulator.getSnapshot()
        val beforeText = before.allLogicalLines().firstOrNull() ?: ""
        assertTrue("Should contain CJK text", beforeText.contains("\u4e16"))

        // Resize narrow and back
        emulator.resizeAndSync(10, 6)  // forces wrap since 8 cols needed
        emulator.resizeAndSync(10, 20)

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()
        assertTrue(
            "CJK text should survive round-trip",
            afterLogical.any { it == beforeText }
        )
    }

    // ================================================================================
    // Test 13: Multiple consecutive resizes
    // ================================================================================

    @Test
    fun testConsecutiveResizes() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        val originalText = "The quick brown fox jumps"
        emulator.writeAndSync(originalText)

        // Resize through various widths
        emulator.resizeAndSync(10, 20)
        emulator.resizeAndSync(10, 10)
        emulator.resizeAndSync(10, 30)
        emulator.resizeAndSync(10, 40)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        assertTrue(
            "Text should be preserved after consecutive resizes",
            logical.any { it == originalText }
        )
    }

    // ================================================================================
    // Test 14: Scrollback push during scroll preserves continuation
    // ================================================================================

    @Test
    fun testScrollbackPushPreservesContinuation() = runBlocking {
        val emulator = createEmulator(rows = 3, cols = 10)

        // Write a long line that wraps (20 chars → 2 lines)
        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRST")

        // Write more lines to push the wrapped lines into scrollback
        emulator.writeAndSync("\r\nX\r\nY\r\nZ\r\n")

        val snapshot = emulator.getSnapshot()
        val scrollback = snapshot.scrollback

        // The wrapped line parts should be in scrollback with correct continuation
        val abcLine = scrollback.indexOfFirst { it.text.trimEnd() == "ABCDEFGHIJ" }
        assertTrue("Should find ABCDEFGHIJ in scrollback", abcLine >= 0)

        if (abcLine + 1 < scrollback.size) {
            assertTrue(
                "KLMNOPQRST should be continuation of ABCDEFGHIJ in scrollback",
                scrollback[abcLine + 1].continuation
            )
            assertEquals(
                "Second part should contain KLMNOPQRST",
                "KLMNOPQRST",
                scrollback[abcLine + 1].text.trimEnd()
            )
        }

        // Verify it's treated as 1 logical line
        val logical = extractLogicalLines(scrollback)
        assertTrue(
            "Should have ABCDEFGHIJKLMNOPQRST as logical line in scrollback",
            logical.any { it == "ABCDEFGHIJKLMNOPQRST" }
        )
    }

    // ================================================================================
    // Test 15: Resize with scrollback content merges correctly
    // ================================================================================

    @Test
    fun testResizeWithScrollbackMergesWrappedLines() = runBlocking {
        val emulator = createEmulator(rows = 3, cols = 10)

        // Create wrapped line in scrollback
        emulator.writeAndSync("ABCDEFGHIJKLMNO")  // wraps at 10
        emulator.writeAndSync("\r\nShort\r\nNext\r\nMore\r\n")  // push to scrollback

        // Resize wider — scrollback wrapped lines should merge
        emulator.resizeAndSync(3, 20)
        val snapshot = emulator.getSnapshot()

        val logical = snapshot.allLogicalLines()
        assertTrue(
            "Should contain merged line 'ABCDEFGHIJKLMNO'",
            logical.any { it == "ABCDEFGHIJKLMNO" }
        )
    }

    // ================================================================================
    // Test 16: Scrollback continuation preserved after resize round-trip
    // ================================================================================

    @Test
    fun testScrollbackContinuationSurvivesResize() = runBlocking {
        val emulator = createEmulator(rows = 3, cols = 10)

        // Create wrapped content and push to scrollback
        emulator.writeAndSync("ABCDEFGHIJKLMNOP")  // wraps to 2 lines
        emulator.writeAndSync("\r\nX\r\nY\r\nZ\r\n")  // push to scrollback

        // Resize narrow → wide
        emulator.resizeAndSync(3, 5)
        emulator.resizeAndSync(3, 20)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        assertTrue(
            "Should contain merged ABCDEFGHIJKLMNOP after round-trip",
            logical.any { it == "ABCDEFGHIJKLMNOP" }
        )
    }

    // ================================================================================
    // Test 17: Verify non-continuation lines don't merge
    // ================================================================================

    @Test
    fun testNonContinuationLinesDontMerge() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 10)

        // Write separate short lines (none should be continuation)
        emulator.writeAndSync("AAA\r\n")
        emulator.writeAndSync("BBB\r\n")
        emulator.writeAndSync("CCC\r\n")

        // Resize wider — these should NOT merge since they're separate logical lines
        emulator.resizeAndSync(10, 30)
        val snapshot = emulator.getSnapshot()

        val logical = snapshot.allLogicalLines()
        assertTrue("Should contain AAA as separate line", logical.contains("AAA"))
        assertTrue("Should contain BBB as separate line", logical.contains("BBB"))
        assertTrue("Should contain CCC as separate line", logical.contains("CCC"))

        // Should NOT have AAABBBCCC as a merged line
        assertFalse(
            "Separate lines should not merge",
            logical.any { it.contains("AAABBB") }
        )
    }

    /**
     * Fire full-screen damage: simulates what happens when the shell responds
     * after SIGWINCH and causes damage callbacks on all rows.
     */
    private fun TerminalEmulatorImpl.fireDamageOnAllRows() {
        val snap = snapshot.value
        damage(0, snap.rows, 0, snap.cols)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        processPendingUpdates()
    }

    // ================================================================================
    // Test 18: Content guard prevents libvterm divergent content from corrupting display
    // After resize, libvterm's screen has its own reflow at different row positions.
    // Without the guard, damage-driven updateLine() would read libvterm's rows and
    // duplicate content. The guard suppresses updateLine() until new input arrives.
    // ================================================================================

    @Test
    fun testCursorLinePreservedAfterResizeAndDamage() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        emulator.writeAndSync("output line 1\r\n")
        emulator.writeAndSync("output line 2\r\n")
        emulator.writeAndSync("user@host:~/project\$ cat file.txt")

        // Resize to width < cursor line length (33 chars > 20 cols)
        emulator.resizeAndSync(10, 20)

        // Fire damage on all rows (simulating post-SIGWINCH callbacks)
        emulator.fireDamageOnAllRows()

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val nonEmpty = snapshot.allNonEmptyLines()
        val allText = nonEmpty.joinToString("|")

        assertTrue("Cursor line should survive resize + damage. Got: $allText",
            logical.any { it.contains("user@host") && it.contains("cat file.txt") })

        // No duplication
        val userHostCount = nonEmpty.count { it.contains("user@host") }
        assertEquals("'user@host' should appear once. Got: $allText", 1, userHostCount)
    }

    @Test
    fun testContentPreservedAfterMultipleResizesWithDamage() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        emulator.writeAndSync("Line1\r\n")
        emulator.writeAndSync("Line2\r\n")
        emulator.writeAndSync("prompt> hello world test string")

        // Progressive narrowing with damage after each resize
        for (cols in listOf(30, 20, 15, 10)) {
            emulator.resizeAndSync(10, cols)
            emulator.fireDamageOnAllRows()
        }

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val nonEmpty = snapshot.allNonEmptyLines()
        val allText = nonEmpty.joinToString("|")

        assertTrue("Line1 should survive. Got: $allText",
            logical.any { it.contains("Line1") })
        assertTrue("Line2 should survive. Got: $allText",
            logical.any { it.contains("Line2") })
        assertTrue("prompt line should survive. Got: $allText",
            logical.any { it.contains("prompt>") && it.contains("hello world") })

        // No cross-line merging
        assertFalse("Line1 and Line2 should not merge. Got: $allText",
            logical.any { it.contains("Line1") && it.contains("Line2") })
        assertFalse("Line2 and prompt should not merge. Got: $allText",
            logical.any { it.contains("Line2") && it.contains("prompt>") })
    }

    @Test
    fun testNoDuplicationAfterResizeBelowCursorLineWidth() = runBlocking {
        val emulator = createEmulator(rows = 8, cols = 30)

        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRSTUVWXYZ")  // 26 chars

        // Resize to width less than text → must wrap
        emulator.resizeAndSync(8, 10)
        emulator.fireDamageOnAllRows()

        val snapshot = emulator.getSnapshot()
        val nonEmpty = snapshot.allNonEmptyLines()
        val allText = nonEmpty.joinToString("|")

        // Should wrap into 3 physical lines
        assertEquals("Should wrap to 3 lines. Got: $allText", 3, nonEmpty.size)
        assertEquals("ABCDEFGHIJ", nonEmpty[0])
        assertEquals("KLMNOPQRST", nonEmpty[1])
        assertEquals("UVWXYZ", nonEmpty[2])

        val logical = snapshot.allLogicalLines()
        assertEquals("Should be 1 logical line", 1, logical.size)
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", logical[0])
    }

    // ================================================================================
    // Test 21: Successive resizes don't merge separate prompts
    // (Reproduces the zoom-in garbling bug where updateLine() overwrites
    // Kotlin-reflow continuation flags with libvterm's divergent state)
    // ================================================================================

    @Test
    fun testSuccessiveResizesDontMergePrompts() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        // Write multiple separate prompt-like lines (each fits in 40 cols)
        emulator.writeAndSync("prompt1> hello world\r\n")
        emulator.writeAndSync("prompt2> foo bar\r\n")
        emulator.writeAndSync("prompt3> baz qux\r\n")

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()
        assertEquals("Should have 3 logical lines before resize", 3, beforeLogical.size)

        // First resize narrower: prompts should wrap but remain separate
        emulator.resizeAndSync(10, 15)

        // Simulate damage processing (as if shell partially redraws after SIGWINCH)
        emulator.processPendingUpdates()

        val afterFirst = emulator.getSnapshot()
        val firstLogical = afterFirst.allLogicalLines()
        assertTrue("prompt1 should survive first resize",
            firstLogical.any { it.contains("prompt1") && it.contains("hello world") })
        assertTrue("prompt2 should survive first resize",
            firstLogical.any { it.contains("prompt2") && it.contains("foo bar") })
        assertTrue("prompt3 should survive first resize",
            firstLogical.any { it.contains("prompt3") && it.contains("baz qux") })

        // Prompts must not merge across line boundaries
        assertFalse("prompt1 and prompt2 should not merge",
            firstLogical.any { it.contains("prompt1") && it.contains("prompt2") })

        // Second resize even narrower: tests that continuation flags survived
        emulator.resizeAndSync(10, 10)
        emulator.processPendingUpdates()

        val afterSecond = emulator.getSnapshot()
        val secondLogical = afterSecond.allLogicalLines()
        assertTrue("prompt1 should survive second resize",
            secondLogical.any { it.contains("prompt1") && it.contains("hello world") })
        assertTrue("prompt2 should survive second resize",
            secondLogical.any { it.contains("prompt2") && it.contains("foo bar") })
        assertTrue("prompt3 should survive second resize",
            secondLogical.any { it.contains("prompt3") && it.contains("baz qux") })

        assertFalse("prompts should not merge after successive resizes",
            secondLogical.any { it.contains("prompt1") && it.contains("prompt2") })
    }

    // ================================================================================
    // Test 19: Rapid resize sequence (simulates pinch-to-zoom)
    // ================================================================================

    @Test
    fun testRapidResizeSequenceDontMergeLines() = runBlocking {
        val emulator = createEmulator(rows = 8, cols = 30)

        emulator.writeAndSync("Line-A short\r\n")
        emulator.writeAndSync("Line-B medium text here\r\n")
        emulator.writeAndSync("Line-C another line\r\n")

        // Rapid successive resizes (simulating zoom gesture)
        for (cols in listOf(25, 20, 15, 12, 10)) {
            emulator.resizeAndSync(8, cols)
        }

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        assertTrue("Line-A should be preserved", logical.any { it.contains("Line-A") })
        assertTrue("Line-B should be preserved", logical.any { it.contains("Line-B") })
        assertTrue("Line-C should be preserved", logical.any { it.contains("Line-C") })

        // None should merge
        assertFalse("Line-A and Line-B should not merge",
            logical.any { it.contains("Line-A") && it.contains("Line-B") })
        assertFalse("Line-B and Line-C should not merge",
            logical.any { it.contains("Line-B") && it.contains("Line-C") })
    }

    // ================================================================================
    // Test 20: Height decrease pushes screen lines to scrollback
    // ================================================================================

    @Test
    fun testHeightDecreasePreservesContent() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        // Fill screen with identifiable lines
        for (i in 1..10) {
            emulator.writeAndSync("Line-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Keyboard opens: height cut in half
        emulator.resizeAndSync(5, 20)
        emulator.fireDamageOnAllRows()

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // ALL lines should still exist (some on screen, some in scrollback)
        for (line in beforeLogical) {
            assertTrue("'$line' should be preserved after height decrease",
                afterLogical.contains(line))
        }

        // The bottom lines should be visible on screen
        val screenLines = after.lines.map { it.text.trimEnd() }.filter { it.isNotEmpty() }
        assertTrue("Last lines should be on screen",
            screenLines.any { it.contains("Line-") })
    }

    // ================================================================================
    // Test 21: Height decrease with large scrollback (3 pages)
    // ================================================================================

    @Test
    fun testHeightDecreaseWithLargeScrollback() = runBlocking {
        val emulator = createEmulator(rows = 24, cols = 40)

        // Write 3 pages of content (72 lines) to fill scrollback
        for (i in 1..72) {
            emulator.writeAndSync("output-line-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()
        assertTrue("Should have many lines", beforeLogical.size >= 24)

        // Keyboard opens: height halved
        emulator.resizeAndSync(12, 40)
        emulator.fireDamageOnAllRows()

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // All content should be preserved in scrollback + screen
        assertEquals("Total logical line count should be preserved",
            beforeLogical.size, afterLogical.size)

        // Verify content is not jumbled: lines should appear in original order
        val afterWithIndex = afterLogical.withIndex().filter { it.value.startsWith("output-line-") }
        for (i in 1 until afterWithIndex.size) {
            val prevNum = afterWithIndex[i - 1].value.substringAfter("output-line-").toIntOrNull() ?: 0
            val currNum = afterWithIndex[i].value.substringAfter("output-line-").toIntOrNull() ?: 0
            assertTrue("Lines should be in order: $prevNum before $currNum",
                currNum > prevNum)
        }

        // No duplication
        val uniqueLines = afterLogical.filter { it.startsWith("output-line-") }.toSet()
        val totalLines = afterLogical.count { it.startsWith("output-line-") }
        assertEquals("No lines should be duplicated", uniqueLines.size, totalLines)
    }

    // ================================================================================
    // Test 22: Height increase restores scrollback lines to screen
    // ================================================================================

    @Test
    fun testHeightIncreaseRestoresFromScrollback() = runBlocking {
        val emulator = createEmulator(rows = 12, cols = 30)

        // Write 3+ pages of content to build large scrollback
        for (i in 1..50) {
            emulator.writeAndSync("Row-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Keyboard closes: height doubles
        emulator.resizeAndSync(24, 30)
        emulator.fireDamageOnAllRows()

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // All content preserved
        for (line in beforeLogical) {
            assertTrue("'$line' should survive height increase",
                afterLogical.contains(line))
        }

        // More lines visible on screen now
        val beforeScreen = before.lines.count { it.text.trimEnd().isNotEmpty() }
        val afterScreen = after.lines.count { it.text.trimEnd().isNotEmpty() }
        assertTrue("More lines visible after height increase: $afterScreen > $beforeScreen",
            afterScreen >= beforeScreen)
    }

    // ================================================================================
    // Test 23: Height decrease → increase round-trip
    // ================================================================================

    @Test
    fun testHeightRoundTrip() = runBlocking {
        val emulator = createEmulator(rows = 24, cols = 30)

        // 2+ pages of content
        for (i in 1..60) {
            emulator.writeAndSync("content-$i\r\n")
        }

        val original = emulator.getSnapshot()
        val originalLogical = original.allLogicalLines()

        // Keyboard open: height halved
        emulator.resizeAndSync(12, 30)
        emulator.fireDamageOnAllRows()

        // Keyboard close: height restored
        emulator.resizeAndSync(24, 30)
        emulator.fireDamageOnAllRows()

        val restored = emulator.getSnapshot()
        val restoredLogical = restored.allLogicalLines()

        // Content should survive the round-trip
        for (line in originalLogical) {
            assertTrue("'$line' should survive height round-trip",
                restoredLogical.contains(line))
        }
    }

    // ================================================================================
    // Test 24: Simultaneous height + width change (keyboard on narrow device)
    // ================================================================================

    @Test
    fun testSimultaneousHeightAndWidthChange() = runBlocking {
        val emulator = createEmulator(rows = 24, cols = 40)

        // 2+ pages of content with longer lines
        for (i in 1..60) {
            emulator.writeAndSync("line-$i with some longer text here\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Keyboard opens: height decreases AND width changes (e.g., landscape keyboard)
        emulator.resizeAndSync(12, 30)
        emulator.fireDamageOnAllRows()

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()
        val allText = after.allNonEmptyLines().joinToString("|")

        // All original content should be preserved
        for (line in beforeLogical) {
            assertTrue("'$line' should survive height+width change. Screen: $allText",
                afterLogical.contains(line))
        }

        // No duplication
        val counts = afterLogical.groupingBy { it }.eachCount()
        val duplicated = counts.filter { it.value > 1 }
        assertTrue("No lines should be duplicated: $duplicated", duplicated.isEmpty())
    }

    // ================================================================================
    // Test 25: Rapid height changes (keyboard bouncing)
    // ================================================================================

    @Test
    fun testRapidHeightChanges() = runBlocking {
        val emulator = createEmulator(rows = 24, cols = 40)

        // 3 pages of content
        for (i in 1..72) {
            emulator.writeAndSync("data-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Rapid keyboard open/close sequence
        for (rows in listOf(12, 18, 10, 24, 8, 20)) {
            emulator.resizeAndSync(rows, 40)
            emulator.fireDamageOnAllRows()
        }

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // All content should survive
        for (line in beforeLogical) {
            assertTrue("'$line' should survive rapid height changes",
                afterLogical.contains(line))
        }

        // Verify order is maintained
        val numbered = afterLogical.filter { it.startsWith("data-") }
            .mapNotNull { it.substringAfter("data-").toIntOrNull() }
        for (i in 1 until numbered.size) {
            assertTrue("Order should be maintained: ${numbered[i-1]} < ${numbered[i]}",
                numbered[i] > numbered[i - 1])
        }
    }

    // ================================================================================
    // Test 26: Height decrease with wrapped lines in scrollback
    // ================================================================================

    @Test
    fun testHeightDecreaseWithWrappedScrollback() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        // Write long lines that wrap
        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n")  // wraps at 20 cols
        emulator.writeAndSync("1234567890abcdefghij\r\n")  // exactly 20 chars
        // Push more lines to move wrapped content to scrollback
        for (i in 1..10) {
            emulator.writeAndSync("short-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()
        assertTrue("Should have ABCDEFGHIJKLMNOPQRSTUVWXYZ as logical line",
            beforeLogical.any { it == "ABCDEFGHIJKLMNOPQRSTUVWXYZ" })

        // Keyboard opens
        emulator.resizeAndSync(5, 20)
        emulator.fireDamageOnAllRows()

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // Wrapped line should still be one logical line
        assertTrue("Wrapped line should survive height decrease",
            afterLogical.any { it == "ABCDEFGHIJKLMNOPQRSTUVWXYZ" })

        // Continuation should be preserved
        val allLines = after.scrollback + after.lines
        val abcLine = allLines.indexOfFirst { it.text.trimEnd() == "ABCDEFGHIJKLMNOPQRST" }
        if (abcLine >= 0 && abcLine + 1 < allLines.size) {
            assertTrue("Continuation should be preserved for wrapped line in scrollback",
                allLines[abcLine + 1].continuation)
        }
    }

    // ================================================================================
    // Test 27: Unwrapping (zoom out) does not introduce spaces between merged content
    // Reproduces the bug where padding cells (' ' from libvterm or '\0' from Kotlin)
    // in lines preceding a continuation leak into the logical line, producing
    // "ABCDEFGHIJ   KLMNO" instead of "ABCDEFGHIJKLMNO" when unwrapped wider.
    // ================================================================================

    @Test
    fun testUnwrapDoesNotIntroduceSpaces() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 20)

        // Write text that fits in 20 cols
        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRST\r\n")  // exactly 20 chars
        emulator.writeAndSync("The quick brown fox!\r\n")   // exactly 20 chars

        // Resize narrower to force wrapping
        emulator.resizeAndSync(10, 10)

        val narrow = emulator.getSnapshot()
        val narrowLogical = narrow.allLogicalLines()
        assertTrue("Should have ABCDEFGHIJKLMNOPQRST",
            narrowLogical.any { it == "ABCDEFGHIJKLMNOPQRST" })

        // Resize back wider — unwrap should NOT introduce spaces
        emulator.resizeAndSync(10, 20)

        val wide = emulator.getSnapshot()
        val wideLogical = wide.allLogicalLines()
        val allText = wide.allNonEmptyLines().joinToString("|")

        assertTrue("Should have clean ABCDEFGHIJKLMNOPQRST without extra spaces. Got: $allText",
            wideLogical.any { it == "ABCDEFGHIJKLMNOPQRST" })
        assertTrue("Should have clean 'The quick brown fox!' without extra spaces. Got: $allText",
            wideLogical.any { it == "The quick brown fox!" })

        // Verify no line contains unexpected internal spaces from padding
        for (line in wideLogical) {
            if (line.contains("ABCDEFGHIJ")) {
                assertEquals("Unwrapped line should be exact",
                    "ABCDEFGHIJKLMNOPQRST", line)
            }
        }
    }

    @Test
    fun testWidenDoesNotInsertEmptyLinesAfterWrappedLines() = runBlocking {
        val wideCols = 120
        val narrowCols = 32
        val emulator = createEmulator(rows = 8, cols = wideCols)

        val lineA =
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json"
        val lineB =
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json"

        emulator.writeAndSync("$lineA\r\n")
        emulator.writeAndSync("$lineB\r\n")

        // Narrow to wrap, then widen back.
        emulator.resizeAndSync(8, narrowCols)
        emulator.resizeAndSync(8, wideCols)

        val snapshot = emulator.getSnapshot()
        val allLines = snapshot.scrollback + snapshot.lines
        val texts = allLines.map { it.text.trimEnd() }

        val indexA = texts.indexOf(lineA)
        val indexB = texts.indexOf(lineB)

        assertTrue("Line A should exist after widen", indexA >= 0)
        assertTrue("Line B should exist after widen", indexB >= 0)
        assertEquals(
            "Widen should not insert empty lines between wrapped lines",
            indexA + 1,
            indexB
        )
        assertFalse(
            "Line between A and B should not be empty",
            allLines[indexA + 1].text.trimEnd().isEmpty()
        )
    }

    @Test
    fun testWidenDoesNotInsertSpacesInsideWrappedWordInScrollback() = runBlocking {
        val wideCols = 90
        val narrowCols = 32
        val rows = 8
        val emulator = createEmulator(rows = rows, cols = wideCols)

        val lineA =
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json"
        val lineB =
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json"

        repeat(5) {
            emulator.writeAndSync("$lineA\r\n")
            emulator.writeAndSync("$lineB\r\n")
        }

        // Narrow to wrap mid-word, then widen back.
        emulator.resizeAndSync(rows, narrowCols)
        emulator.resizeAndSync(rows, wideCols)

        val snapshot = emulator.getSnapshot()
        val logical = extractLogicalLines(snapshot.scrollback + snapshot.lines)

        assertTrue("Expected lineA after unwrap", logical.any { it == lineA })
        assertTrue("Expected lineB after unwrap", logical.any { it == lineB })

        val corrupted = logical.filter {
            it.contains("icons-vscode-icons.meta.js") &&
                !it.contains("icons-vscode-icons.meta.json")
        }
        assertTrue(
            "No mid-word spaces should be inserted during unwrap. Corrupted=$corrupted",
            corrupted.isEmpty()
        )
    }

    @Test
    fun testUnwrapDoesNotIntroduceMidWordSpacesFromTrailingPadding() = runBlocking {
        val wideCols = 90
        val narrowCols = 32
        val rows = 8
        val emulator = createEmulator(rows = rows, cols = wideCols)

        val line = "-rw-r--r--@   1 salim  staff   555B Feb 17 01:08 cloudbuild.yaml"

        repeat(3) {
            emulator.writeAndSync("$line\r\n")
        }

        // Narrow to wrap in the middle of "cloudbuild.yaml", then widen back.
        emulator.resizeAndSync(rows, narrowCols)
        emulator.resizeAndSync(rows, wideCols)

        val snapshot = emulator.getSnapshot()
        val logical = extractLogicalLines(snapshot.scrollback + snapshot.lines)

        assertTrue("Expected line after unwrap", logical.any { it == line })

        val corrupted = logical.filter {
            it.contains("cloudbuild.y") && it.contains("aml") && it != line
        }
        assertTrue(
            "Unwrap should not insert spaces inside cloudbuild.yaml. Corrupted=$corrupted",
            corrupted.isEmpty()
        )
    }

    @Test
    fun testTrailingPaddingNotStoredAsSpaces() = runBlocking {
        val emulator = createEmulator(rows = 6, cols = 80)

        emulator.writeAndSync("short-line\r\n")
        emulator.writeAndSync("another-short-line\r\n")

        val snapshot = emulator.getSnapshot()
        val lines = snapshot.scrollback + snapshot.lines
        val hasSpaces = lines.any { hasTrailingSpaces(it.cells) }

        assertFalse(
            "Trailing padding should not be stored as spaces in line cells",
            hasSpaces
        )
    }

    @Test
    fun testUnwrapWithScrollbackDoesNotIntroduceSpaces() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 30)

        // Write 2+ pages of long lines that will wrap when narrowed
        for (i in 1..20) {
            emulator.writeAndSync("line-$i-abcdefghijklmnopqrstuvw\r\n")  // 30 chars
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Narrow → content wraps → some goes to scrollback
        emulator.resizeAndSync(5, 15)

        // Widen back — unwrap should not add spaces
        emulator.resizeAndSync(5, 30)

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // Every original line should be preserved without internal padding spaces
        for (line in beforeLogical) {
            assertTrue(
                "'$line' should survive narrow→wide round-trip without extra spaces",
                afterLogical.any { it == line }
            )
        }
    }

    @Test
    fun testUnwrapAfterDamageSyncDoesNotIntroduceSpaces() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 30)

        // Write content that will wrap
        emulator.writeAndSync("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234\r\n")  // 30 chars (exact)
        emulator.writeAndSync("Hello World Test Line Content!\r\n")   // 30 chars

        // Narrow — wraps to multiple lines
        emulator.resizeAndSync(10, 15)

        // Simulate shell SIGWINCH response: damage all rows (reads libvterm's ' ' padding)
        emulator.fireDamageOnAllRows()

        // Widen — unwrap the content
        emulator.resizeAndSync(10, 30)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val allText = snapshot.allNonEmptyLines().joinToString("|")

        assertTrue("Should unwrap cleanly: ABCDEFGHIJKLMNOPQRSTUVWXYZ1234. Got: $allText",
            logical.any { it == "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234" })
        assertTrue("Should unwrap cleanly: Hello World Test Line Content!. Got: $allText",
            logical.any { it == "Hello World Test Line Content!" })
    }

    @Test
    fun testMultipleNarrowWidenCyclesNoSpaceAccumulation() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        // Write 2 pages of varied-length content
        for (i in 1..30) {
            val text = "data-$i-" + "x".repeat(20 - "data-$i-".length)
            emulator.writeAndSync("$text\r\n")
        }

        val original = emulator.getSnapshot()
        val originalLogical = original.allLogicalLines()

        // Multiple narrow→wide cycles: spaces should NOT accumulate
        for (cycle in 1..3) {
            emulator.resizeAndSync(10, 15)
            emulator.resizeAndSync(10, 40)
        }

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        for (line in originalLogical) {
            assertTrue("'$line' should survive 3 narrow/wide cycles without space accumulation",
                afterLogical.any { it == line })
        }
    }

    // ================================================================================
    // Test 28: Shell output after resize doesn't garble screen
    // This simulates the real-world scenario: resize triggers SIGWINCH, shell redraws,
    // and the new shell output arrives via writeInput. With the old per-row protection
    // architecture this caused garbling because libvterm and Kotlin had divergent rows.
    // ================================================================================

    @Test
    fun testShellOutputAfterResizePreservesContent() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        emulator.writeAndSync("prompt1> first command output\r\n")
        emulator.writeAndSync("prompt2> second command output\r\n")
        emulator.writeAndSync("prompt3> ")

        // Resize narrower (simulates zoom in)
        emulator.resizeAndSync(10, 20)

        // Shell responds to SIGWINCH by writing new output
        emulator.writeAndSync("new-output\r\n")
        emulator.writeAndSync("prompt4> ")

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val allText = snapshot.allNonEmptyLines().joinToString("|")

        // Previous prompts should still be intact
        assertTrue("prompt1 line should survive. Got: $allText",
            logical.any { it.contains("prompt1") && it.contains("first command") })
        assertTrue("prompt2 line should survive. Got: $allText",
            logical.any { it.contains("prompt2") && it.contains("second command") })

        // New output should be present
        assertTrue("new-output should be present. Got: $allText",
            logical.any { it.contains("new-output") })
        assertTrue("prompt4 should be present. Got: $allText",
            logical.any { it.contains("prompt4") })

        // No cross-line merging
        assertFalse("Prompts should not merge. Got: $allText",
            logical.any { it.contains("prompt1") && it.contains("prompt2") })
    }

    // ================================================================================
    // Test 31: Trailing padding in scrollback does not reflow into empty lines
    // Reproduces the "invisible trailing chars create empty line on zoom" issue.
    // ================================================================================

    @Test
    fun testScrollbackPaddingDoesNotCreateEmptyContinuationLines() = runBlocking {
        val emulator = createEmulator(rows = 4, cols = 40)

        // Create scrollback with long lines that will reflow multiple times.
        for (i in 1..30) {
            emulator.writeAndSync("line-$i-abcdefghijklmnopqrstuvwxyz012345\r\n")
        }

        // Multiple width changes (zoom in/out) to exercise reflow on scrollback
        emulator.resizeAndSync(4, 20)
        emulator.resizeAndSync(4, 30)
        emulator.resizeAndSync(4, 10)
        emulator.resizeAndSync(4, 25)

        val snapshot = emulator.getSnapshot()
        val allLines = snapshot.scrollback + snapshot.lines

        val emptyContinuation = allLines.any {
            it.continuation && it.text.trimEnd().isEmpty()
        }
        assertFalse(
            "Scrollback reflow should not create empty continuation lines",
            emptyContinuation
        )
    }

    // ================================================================================
    // Test 29: Multiple resizes with shell output between each
    // ================================================================================

    @Test
    fun testMultipleResizesWithShellOutputBetween() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        emulator.writeAndSync("initial-line-1\r\n")
        emulator.writeAndSync("initial-line-2\r\n")

        // First resize + shell output
        emulator.resizeAndSync(10, 25)
        emulator.writeAndSync("after-resize-1\r\n")

        // Second resize + shell output
        emulator.resizeAndSync(10, 15)
        emulator.writeAndSync("after-resize-2\r\n")

        // Third resize back wider + shell output
        emulator.resizeAndSync(10, 40)
        emulator.writeAndSync("after-resize-3\r\n")

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val allText = snapshot.allNonEmptyLines().joinToString("|")

        assertTrue("initial-line-1 should survive. Got: $allText",
            logical.any { it.contains("initial-line-1") })
        assertTrue("initial-line-2 should survive. Got: $allText",
            logical.any { it.contains("initial-line-2") })
        assertTrue("after-resize-1 should be present. Got: $allText",
            logical.any { it.contains("after-resize-1") })
        assertTrue("after-resize-2 should be present. Got: $allText",
            logical.any { it.contains("after-resize-2") })
        assertTrue("after-resize-3 should be present. Got: $allText",
            logical.any { it.contains("after-resize-3") })
    }

    // ================================================================================
    // Test 30: Large scrollback with resize and shell output
    // ================================================================================

    @Test
    fun testLargeScrollbackResizeWithShellOutput() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 40)

        // Fill up scrollback with identifiable lines
        for (i in 1..50) {
            emulator.writeAndSync("scrollback-line-$i\r\n")
        }

        val beforeLogical = emulator.getSnapshot().allLogicalLines()

        // Resize narrower
        emulator.resizeAndSync(10, 20)

        // Shell writes new output after resize
        emulator.writeAndSync("post-resize-output\r\n")
        emulator.writeAndSync("new-prompt> ")

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        // Recent scrollback lines should be preserved
        assertTrue("scrollback-line-50 should survive",
            logical.any { it.contains("scrollback-line-50") })
        assertTrue("scrollback-line-49 should survive",
            logical.any { it.contains("scrollback-line-49") })

        // New output should be present
        assertTrue("post-resize-output should be present",
            logical.any { it.contains("post-resize-output") })
        assertTrue("new-prompt should be present",
            logical.any { it.contains("new-prompt") })

        // No duplication of scrollback content
        val line50Count = logical.count { it.contains("scrollback-line-50") }
        assertEquals("scrollback-line-50 should appear exactly once", 1, line50Count)
    }

    // ================================================================================
    // Test 31: Resize narrower then wider with scrollback integrity
    // ================================================================================

    @Test
    fun testScrollbackIntegrityAfterResizeRoundTrip() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 30)

        // Write lines that will be in scrollback
        for (i in 1..20) {
            emulator.writeAndSync("entry-$i-data\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeLogical = before.allLogicalLines()

        // Resize narrow (wraps scrollback content)
        emulator.resizeAndSync(5, 15)

        // Resize back wide (unwraps)
        emulator.resizeAndSync(5, 30)

        val after = emulator.getSnapshot()
        val afterLogical = after.allLogicalLines()

        // All original lines should be preserved and match exactly
        for (line in beforeLogical) {
            assertTrue("'$line' should survive narrow→wide round-trip in scrollback",
                afterLogical.any { it == line })
        }

        // Order should be preserved
        val beforeEntries = beforeLogical.filter { it.startsWith("entry-") }
            .mapNotNull { it.substringAfter("entry-").substringBefore("-").toIntOrNull() }
        val afterEntries = afterLogical.filter { it.startsWith("entry-") }
            .mapNotNull { it.substringAfter("entry-").substringBefore("-").toIntOrNull() }

        assertEquals("Same number of entries", beforeEntries.size, afterEntries.size)
        for (i in afterEntries.indices) {
            if (i > 0) {
                assertTrue("Order preserved: ${afterEntries[i-1]} < ${afterEntries[i]}",
                    afterEntries[i] > afterEntries[i - 1])
            }
        }
    }

    // ================================================================================
    // Diagnostic: Cell-level verification after widen resize
    // ================================================================================

    /**
     * Helper: find the last non-blank cell index in a line.
     * Returns -1 if all cells are blank.
     */
    private fun lastContentIndex(cells: List<TerminalLine.Cell>): Int {
        for (i in cells.indices.reversed()) {
            val c = cells[i]
            if (c.char != '\u0000' && c.char != ' ') return i
            if (c.combiningChars.isNotEmpty()) return i
        }
        return -1
    }

    /**
     * Helper: describe cell contents for diagnostics.
     */
    private fun describeCells(cells: List<TerminalLine.Cell>, from: Int, to: Int): String {
        return buildString {
            for (i in from until to.coerceAtMost(cells.size)) {
                val c = cells[i]
                when {
                    c.char == '\u0000' -> append("\\0")
                    c.char == ' ' -> append("SP")
                    c.char.code in 1..31 -> append("^${('A' + c.char.code - 1)}")
                    else -> append("'${c.char}'")
                }
                if (i < to - 1) append(",")
            }
        }
    }

    /**
     * Verify that scrollback lines don't have visible non-blank characters beyond
     * the actual content after a widen resize. This catches the bug where trailing
     * empty cells are converted to visible "black box" characters.
     */
    @Test
    fun testWidenDoesNotAddTrailingVisibleCharsToScrollback() = runBlocking {
        val initialCols = 30
        val widenedCols = 40
        val emulator = createEmulator(rows = 5, cols = initialCols)

        // Write short lines that don't fill the full width.
        // "short-XX" is ~8 chars on a 30-col terminal, leaving 22 blank cells.
        for (i in 1..10) {
            emulator.writeAndSync("short-${"%02d".format(i)}\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeScrollbackCount = before.scrollback.size
        assertTrue("Should have scrollback lines", beforeScrollbackCount > 0)

        // Record what each scrollback line looks like (text content + cell count)
        val beforeInfo = before.scrollback.map { line ->
            val lastContent = lastContentIndex(line.cells)
            Triple(line.text.trimEnd(), line.cells.size, lastContent)
        }

        // Widen the terminal
        emulator.resizeAndSync(5, widenedCols)
        val after = emulator.getSnapshot()

        // Check every scrollback line: no visible chars beyond original content
        for (i in after.scrollback.indices) {
            val line = after.scrollback[i]
            val lastContent = lastContentIndex(line.cells)
            val textContent = line.text.trimEnd()

            // Every cell beyond lastContent should be '\u0000' (null) — NOT space, NOT any
            // other visible character. If cells beyond content are ' ' (space), they will
            // render as background only. But any char > ' ' beyond content is a bug.
            for (cellIdx in (lastContent + 1) until line.cells.size) {
                val cell = line.cells[cellIdx]
                assertTrue(
                    "Scrollback line $i ('$textContent'): cell $cellIdx has " +
                        "unexpected char '${cell.char}' (code=${cell.char.code}). " +
                        "Cells around boundary: ${describeCells(line.cells, maxOf(0, lastContent - 2), minOf(line.cells.size, cellIdx + 3))}",
                    cell.char == '\u0000' || cell.char == ' '
                )
            }
        }
    }

    /**
     * Verify that after multiple widen cycles, scrollback lines remain clean.
     * Each cycle should NOT progressively add visible characters.
     */
    @Test
    fun testMultipleWidenCyclesNoProgressiveCorruption() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 30)

        // Write short lines that push into scrollback
        for (i in 1..15) {
            emulator.writeAndSync("line-${"%02d".format(i)}\r\n")
        }

        // Record original scrollback content
        val original = emulator.getSnapshot()
        val originalScrollback = original.scrollback.map { it.text.trimEnd() }

        // Do 5 narrow→widen cycles (simulating repeated pinch zoom)
        for (cycle in 1..5) {
            emulator.resizeAndSync(5, 25)  // narrow
            emulator.resizeAndSync(5, 35)  // widen past original

            val snap = emulator.getSnapshot()

            // Check: no scrollback line should have cells with visible chars
            // beyond the actual text content
            for (i in snap.scrollback.indices) {
                val line = snap.scrollback[i]
                val lastContent = lastContentIndex(line.cells)

                for (cellIdx in (lastContent + 1) until line.cells.size) {
                    val cell = line.cells[cellIdx]
                    assertFalse(
                        "Cycle $cycle, scrollback line $i ('${line.text.trimEnd()}'): " +
                            "cell $cellIdx has visible char '${cell.char}' (code=${cell.char.code})",
                        cell.char != '\u0000' && cell.char != ' '
                    )
                }
            }
        }

        // Final check: content should be preserved (logical lines match)
        val finalSnap = emulator.getSnapshot()
        val finalLogical = extractLogicalLines(finalSnap.scrollback + finalSnap.lines)
            .filter { it.startsWith("line-") }

        for (line in originalScrollback.filter { it.startsWith("line-") }) {
            assertTrue("'$line' should survive 5 resize cycles",
                finalLogical.any { it == line })
        }
    }

    /**
     * Verify that the number of non-blank cells in scrollback lines doesn't
     * grow after each widen (the "progressive black box" bug).
     */
    @Test
    fun testScrollbackCellCountDoesNotGrowOnWiden() = runBlocking {
        val emulator = createEmulator(rows = 5, cols = 40)

        // Write lines of varying lengths
        emulator.writeAndSync("A\r\n")                    // 1 char
        emulator.writeAndSync("Hello World\r\n")           // 11 chars
        emulator.writeAndSync("Medium length content\r\n") // 21 chars
        for (i in 1..5) {
            emulator.writeAndSync("filler-$i\r\n")
        }

        val before = emulator.getSnapshot()
        val beforeContentWidths = before.scrollback.map { line ->
            lastContentIndex(line.cells) + 1
        }

        // Widen
        emulator.resizeAndSync(5, 50)
        val after = emulator.getSnapshot()
        val afterContentWidths = after.scrollback.map { line ->
            lastContentIndex(line.cells) + 1
        }

        // Content widths should NOT grow — they should stay the same or shrink
        // (if continuation groups were merged).
        // They should NEVER be larger than before + the original content length.
        val afterLogical = after.scrollback.map { it.text.trimEnd() }
        for (i in afterLogical.indices) {
            val line = after.scrollback[i]
            val contentWidth = lastContentIndex(line.cells) + 1
            val textLen = line.text.trimEnd().length

            assertTrue(
                "Scrollback line $i: content width ($contentWidth) should not exceed " +
                    "text length ($textLen). Cells: ${describeCells(line.cells, 0, line.cells.size)}",
                contentWidth <= textLen + 1  // +1 for possible combining chars
            )
        }
    }

    // ================================================================================
    // Color mismatch diagnostics
    // ================================================================================

    /**
     * Verify that Color(argbInt) == Color(r, g, b) for the same color.
     * If this fails, the renderer's background comparison is broken.
     */
    @Test
    fun testColorConstructorEquality() {
        // Test with pure black (the default)
        val blackFromInt = Color(0xFF000000.toInt())
        val blackFromRGB = Color(0, 0, 0)
        assertEquals(
            "Color.Black: Color(Int) should equal Color(r,g,b). " +
                "fromInt=${blackFromInt.value}, fromRGB=${blackFromRGB.value}",
            blackFromInt, blackFromRGB
        )

        // Test with a typical dark theme color (#1E1E2E)
        val darkFromInt = Color(0xFF1E1E2E.toInt())
        val darkFromRGB = Color(0x1E, 0x1E, 0x2E)
        assertEquals(
            "Dark theme: Color(Int) should equal Color(r,g,b). " +
                "fromInt=${darkFromInt.value}, fromRGB=${darkFromRGB.value}",
            darkFromInt, darkFromRGB
        )

        // Test with a mid-range color (#8B5CF6 - purple)
        val purpleFromInt = Color(0xFF8B5CF6.toInt())
        val purpleFromRGB = Color(0x8B, 0x5C, 0xF6)
        assertEquals(
            "Purple: Color(Int) should equal Color(r,g,b). " +
                "fromInt=${purpleFromInt.value}, fromRGB=${purpleFromRGB.value}",
            purpleFromInt, purpleFromRGB
        )

        // Test round-trip: Color(Int) → toArgb() → Color(Int)
        val original = Color(0xFF1E1E2E.toInt())
        val roundTrip = Color(original.toArgb())
        assertEquals(
            "Round-trip: Color(Int) → toArgb() → Color(Int) should be equal",
            original, roundTrip
        )
    }

    /**
     * Verify that cell bgColors in the snapshot match the renderer's backgroundColor
     * after applying a theme and resizing. This catches the exact scenario where
     * black boxes appear due to Color comparison mismatch.
     */
    @Test
    fun testCellBgColorMatchesDefaultAfterThemeAndResize() = runBlocking {
        val themeBg = 0xFF1E1E2E.toInt()  // Dark theme background
        val themeFg = 0xFFCDD6F4.toInt()  // Light foreground

        val emulator = createEmulator(rows = 5, cols = 30)
        emulator.setDefaultColors(themeFg, themeBg)

        // Write some content
        for (i in 1..8) {
            emulator.writeAndSync("line-$i\r\n")
        }

        val rendererBgColor = Color(themeBg)

        // Check before resize: do cell bgColors match the renderer's bgColor?
        val before = emulator.getSnapshot()
        var mismatchCount = 0
        for (line in before.scrollback + before.lines) {
            for ((idx, cell) in line.cells.withIndex()) {
                if (cell.char == '\u0000' || cell.char == ' ') {
                    if (cell.bgColor != rendererBgColor) {
                        mismatchCount++
                    }
                }
            }
        }
        assertEquals(
            "Before resize: empty cells should have bgColor matching renderer. " +
                "rendererBg=${rendererBgColor.value}, mismatches=$mismatchCount",
            0, mismatchCount
        )

        // Widen
        emulator.resizeAndSync(5, 40)

        val after = emulator.getSnapshot()
        mismatchCount = 0
        var firstMismatch = ""
        for ((lineIdx, line) in (after.scrollback + after.lines).withIndex()) {
            for ((cellIdx, cell) in line.cells.withIndex()) {
                if (cell.char == '\u0000' || cell.char == ' ') {
                    if (cell.bgColor != rendererBgColor) {
                        mismatchCount++
                        if (firstMismatch.isEmpty()) {
                            firstMismatch = "line=$lineIdx cell=$cellIdx " +
                                "cellBg=${cell.bgColor.value} rendererBg=${rendererBgColor.value}"
                        }
                    }
                }
            }
        }
        assertEquals(
            "After widen: empty cells should have bgColor matching renderer. " +
                "First mismatch: $firstMismatch",
            0, mismatchCount
        )
    }

    /**
     * Test progressive resize scenario: after the first widen trims scrollback lines,
     * a second widen may pop those trimmed lines back to the screen. The null-fill
     * cells in the pop callback must use correct default colors, not hard-coded black.
     *
     * This reproduces the "black box characters" bug seen in omni's terminal:
     * 1. Narrow the terminal to create continuation lines on screen
     * 2. Widen back — this trims scrollback lines (via reflowLines) and pops some
     * 3. Widen again — popped trimmed lines have fewer cells → null-fill positions
     */
    @Test
    fun testProgressiveWidenDoesNotIntroduceColorMismatchInPoppedLines() = runBlocking {
        val themeBg = 0xFF1E1E2E.toInt()  // Dark theme background (NOT black)
        val themeFg = 0xFFCDD6F4.toInt()  // Light foreground

        val rows = 10
        val emulator = createEmulator(rows = rows, cols = 20)
        emulator.setDefaultColors(themeFg, themeBg)

        val rendererBgColor = Color(themeBg)

        // Write 15 lines of 18 chars at 20 cols — each fits in 1 row, no wrap
        for (i in 1..15) {
            val content = "L%02d-".format(i) + "X".repeat(14)  // 18 chars each
            emulator.writeAndSync("$content\r\n")
        }

        val snap0 = emulator.getSnapshot()
        val scrollback0 = snap0.scrollback.size

        // Step 1: Narrow to 10 cols — all lines wrap to 2 rows each
        // Screen can only hold 10 rows = 5 logical lines (10 wrapped rows)
        // Remaining lines pushed to scrollback
        emulator.resizeAndSync(rows, 10)
        val snap1 = emulator.getSnapshot()
        val scrollback1 = snap1.scrollback.size

        // Step 2: Widen to 25 cols — wrapped lines unwrap, freeing rows
        // libvterm should pop scrollback lines to fill freed screen rows
        emulator.resizeAndSync(rows, 25)
        val snap2 = emulator.getSnapshot()
        val scrollback2 = snap2.scrollback.size

        // Verify scrollback decreased (confirming pops happened)
        assertTrue(
            "Scrollback should decrease after widen (pops). Before: $scrollback1, after: $scrollback2",
            scrollback2 < scrollback1
        )

        // Step 3: Widen again — this time scrollback lines were trimmed by step 2's reflow
        emulator.resizeAndSync(rows, 30)
        val snap3 = emulator.getSnapshot()

        // Check ALL lines for empty cells with wrong bgColor
        var mismatchCount = 0
        val mismatches = mutableListOf<String>()
        val allLines = snap3.scrollback + snap3.lines
        for ((lineIdx, line) in allLines.withIndex()) {
            for ((cellIdx, cell) in line.cells.withIndex()) {
                if (cell.char == '\u0000' || cell.char == ' ') {
                    if (cell.bgColor != rendererBgColor) {
                        mismatchCount++
                        if (mismatches.size < 10) {
                            val inScrollback = lineIdx < snap3.scrollback.size
                            val region = if (inScrollback) "scrollback" else "screen"
                            mismatches.add(
                                "$region line=$lineIdx cell=$cellIdx " +
                                    "char='${if (cell.char == '\u0000') "\\0" else cell.char}' " +
                                    "cellBg=0x${cell.bgColor.toArgb().toUInt().toString(16)} " +
                                    "expected=0x${rendererBgColor.toArgb().toUInt().toString(16)}"
                            )
                        }
                    }
                }
            }
        }
        assertEquals(
            "After narrow→widen→widen: empty cells should have bgColor matching renderer. " +
                "$mismatchCount mismatches. First few:\n${mismatches.joinToString("\n")}",
            0, mismatchCount
        )
    }

    /**
     * Reproduce the "black box characters" bug: when a ROW-ONLY resize
     * (same cols, more rows) pops scrollback lines that were previously
     * trimmed by a column-changing reflow, the null-fill cells in the
     * JNI pop callback must use the terminal's default colors — not
     * hard-coded black. This is the exact scenario triggered by a very
     * small pinch-to-zoom on Android (font shrinks, rows increase, but
     * cols stays the same).
     */
    @Test
    fun testRowOnlyResizePopDoesNotIntroduceBlackCells() = runBlocking {
        val themeBg = 0xFF1E1E2E.toInt()
        val themeFg = 0xFFCDD6F4.toInt()

        val initialRows = 5
        val initialCols = 40
        val emulator = createEmulator(rows = initialRows, cols = initialCols)
        emulator.setDefaultColors(themeFg, themeBg)

        val rendererBgColor = Color(themeBg)

        // Write short lines (shorter than cols) to fill scrollback
        for (i in 1..20) {
            emulator.writeAndSync("prompt-$i> ls\r\n")  // ~14 chars on 40-col terminal
        }

        val snap0 = emulator.getSnapshot()
        assertTrue("Should have scrollback", snap0.scrollback.size > 0)

        // Step 1: Column-changing resize → reflow trims scrollback lines
        val widenedCols = 50
        emulator.resizeAndSync(initialRows, widenedCols)

        val snap1 = emulator.getSnapshot()
        // Verify scrollback lines are now trimmed (fewer cells than widenedCols)
        val trimmedLine = snap1.scrollback.firstOrNull { it.cells.size < widenedCols }
        assertTrue(
            "Scrollback lines should be trimmed after reflow. " +
                "First line cells: ${snap1.scrollback.firstOrNull()?.cells?.size}",
            trimmedLine != null
        )

        // Step 2: ROW-ONLY resize (more rows, same cols) → triggers pops
        // This is the scenario: small pinch increases rows, cols unchanged
        val moreRows = initialRows + 5  // 10 rows now
        emulator.resizeAndSync(moreRows, widenedCols)

        val snap2 = emulator.getSnapshot()

        // Verify pops happened (scrollback should decrease)
        assertTrue(
            "Scrollback should decrease after row increase (pops). " +
                "Before: ${snap1.scrollback.size}, after: ${snap2.scrollback.size}",
            snap2.scrollback.size < snap1.scrollback.size
        )

        // THE CRITICAL CHECK: screen lines from popped scrollback should NOT
        // have trailing cells with Color.Black bg (the old hard-coded value)
        var mismatchCount = 0
        val mismatches = mutableListOf<String>()
        val allLines = snap2.scrollback + snap2.lines
        for ((lineIdx, line) in allLines.withIndex()) {
            for ((cellIdx, cell) in line.cells.withIndex()) {
                if (cell.char == '\u0000' || cell.char == ' ') {
                    if (cell.bgColor != rendererBgColor) {
                        mismatchCount++
                        if (mismatches.size < 10) {
                            val inScrollback = lineIdx < snap2.scrollback.size
                            val region = if (inScrollback) "scrollback" else "screen"
                            mismatches.add(
                                "$region line=$lineIdx cell=$cellIdx " +
                                    "char='${if (cell.char == '\u0000') "\\0" else cell.char}' " +
                                    "cellBg=0x${cell.bgColor.toArgb().toUInt().toString(16)} " +
                                    "expected=0x${rendererBgColor.toArgb().toUInt().toString(16)}"
                            )
                        }
                    }
                }
            }
        }
        assertEquals(
            "Row-only resize with pops: null-fill cells should use default colors, not black. " +
                "$mismatchCount mismatches. First few:\n${mismatches.joinToString("\n")}",
            0, mismatchCount
        )
    }

    /**
     * Test that three consecutive widen resizes don't progressively accumulate
     * color mismatches in scrollback/screen lines.
     */
    @Test
    fun testTripleWidenNoCumulativeColorCorruption() = runBlocking {
        val themeBg = 0xFF1E1E2E.toInt()
        val themeFg = 0xFFCDD6F4.toInt()

        val rows = 10
        val emulator = createEmulator(rows = rows, cols = 20)
        emulator.setDefaultColors(themeFg, themeBg)

        val rendererBgColor = Color(themeBg)

        // Write 15 lines of 18 chars — each fits in 20 cols
        for (i in 1..15) {
            val content = "L%02d-".format(i) + "A".repeat(14)  // 18 chars
            emulator.writeAndSync("$content\r\n")
        }

        // Narrow first to create wrapped content → then do successive widens
        emulator.resizeAndSync(rows, 10)

        // Three successive widens (simulating progressive pinch-to-zoom out)
        val widths = listOf(22, 24, 26)
        for ((step, newCols) in widths.withIndex()) {
            emulator.resizeAndSync(rows, newCols)

            val snap = emulator.getSnapshot()
            var mismatchCount = 0
            var firstMismatch = ""
            val allLines = snap.scrollback + snap.lines
            for ((lineIdx, line) in allLines.withIndex()) {
                for ((cellIdx, cell) in line.cells.withIndex()) {
                    if (cell.char == '\u0000' || cell.char == ' ') {
                        if (cell.bgColor != rendererBgColor) {
                            mismatchCount++
                            if (firstMismatch.isEmpty()) {
                                val inScrollback = lineIdx < snap.scrollback.size
                                firstMismatch = "${if (inScrollback) "sb" else "scr"} " +
                                    "line=$lineIdx cell=$cellIdx " +
                                    "char='${if (cell.char == '\u0000') "\\0" else cell.char}' " +
                                    "bg=0x${cell.bgColor.toArgb().toUInt().toString(16)} " +
                                    "expected=0x${rendererBgColor.toArgb().toUInt().toString(16)}"
                            }
                        }
                    }
                }
            }
            assertEquals(
                "Step $step (cols=$newCols): empty cells should have correct bgColor. " +
                    "$mismatchCount mismatches. First: $firstMismatch",
                0, mismatchCount
            )
        }
    }

    // ================================================================================
    // Test 32: `ll` output preserves column spacing after zoom round-trip
    // Reproduces: type `ll` three times, zoom in to ~32 cols, then zoom out.
    // ================================================================================ 

    @Test
    fun testLlOutputPreservesColumnSpacingAfterZoomRoundTrip() = runBlocking {
        val emulator = createEmulator(rows = 10, cols = 90)

        val llLines = listOf(
            "total 204816",
            "drwxr-xr-x@   2 salim  staff    64B Feb 17 01:08 code",
            "-rw-r--r--@   1 salim  staff   4.3K Feb 17 01:08 CONTRIBUTING.md",
            "-rw-r--r--@   1 salim  staff   5.4K Feb 17 01:08 INSTALL.md",
            "-rw-r--r--@   1 salim  staff   1.7K Feb 17 01:08 README.md",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 17 01:08 RELEASING.md",
            "-rw-r--r--@   1 salim  staff    99M Feb 22 19:01 app-production-release.apk",
            "-rw-r--r--@   1 salim  staff   555B Feb 17 01:08 cloudbuild.yaml",
            "-rw-r--r--@   1 salim  staff   6.7K Feb 17 01:08 errors.md",
            "-rw-r--r--@   1 salim  staff   723B Feb 17 01:08 firebase.json",
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json",
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json",
            "drwxr-xr-x@ 762 salim  staff    24K Feb 25 23:24 node_modules",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 25 23:16 package.json",
            "drwxr-xr-x@   7 salim  staff   224B Feb 17 01:08 packages",
            "-rw-r--r--@   1 salim  staff   500K Feb 25 22:07 pnpm-lock.yaml",
            "-rw-r--r--@   1 salim  staff    27B Feb 17 01:08 pnpm-workspace.yaml",
            "drwxr-xr-x@  17 salim  staff   544B Feb 25 23:24 scripts",
            "-rw-r--r--@   1 salim  staff   513B Feb 17 01:08 tsconfig.base.json",
            "-rw-r--r--@   1 salim  staff   534B Feb 17 01:08 turbo.json"
        )

        repeat(3) {
            emulator.writeAndSync("prompt$ ll\r\n")
            for (line in llLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        // Zoom in (narrow) then zoom out (wide again).
        emulator.resizeAndSync(10, 32)
        emulator.resizeAndSync(10, 90)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()

        for (line in llLines) {
            assertTrue(
                "Line should be preserved with column spacing: '$line'",
                logical.contains(line)
            )
        }
    }

    // ================================================================================
    // Test 33: Line-mode copy trims trailing padding on each line
    // ================================================================================ 

    @Test
    fun testLineModeCopyTrimsTrailingPaddingPerLine() = runBlocking {
        val emulator = createEmulator(rows = 4, cols = 20)

        emulator.writeAndSync("ABC\r\n")
        emulator.writeAndSync("DEF\r\n")

        val snapshot = emulator.getSnapshot()
        val selectionManager = SelectionManager()
        selectionManager.startSelection(row = 0, col = 0, cols = snapshot.cols, mode = SelectionMode.LINE)
        selectionManager.updateSelection(1, 0)
        selectionManager.endSelection()

        val copied = selectionManager.getSelectedText(snapshot, scrollbackPosition = 0)
        assertEquals("ABC\nDEF", copied)
    }

    // ================================================================================
    // Test 34: ll output survives zoom out -> in -> out with no internal NULs
    // Reproduces: repeated `ll`, zoom in to ~32 cols, then zoom out.
    // ================================================================================ 

    @Test
    fun testLlOutputNoInternalNulAfterZoomRoundTrip() = runBlocking {
        val rows = 12
        val wideCols = 90
        val narrowCols = 32
        val emulator = createEmulator(rows = rows, cols = wideCols)

        val llLines = listOf(
            "total 204816",
            "drwxr-xr-x@   2 salim  staff    64B Feb 17 01:08 1code",
            "-rw-r--r--@   1 salim  staff   4.3K Feb 17 01:08 CONTRIBUTING.md",
            "-rw-r--r--@   1 salim  staff   5.4K Feb 17 01:08 INSTALL.md",
            "-rw-r--r--@   1 salim  staff   1.7K Feb 17 01:08 README.md",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 17 01:08 RELEASING.md",
            "-rw-r--r--@   1 salim  staff    99M Feb 22 19:01 app-production-release.apk",
            "-rw-r--r--@   1 salim  staff   555B Feb 17 01:08 cloudbuild.yaml",
            "-rw-r--r--@   1 salim  staff   6.7K Feb 17 01:08 errors.md",
            "-rw-r--r--@   1 salim  staff   723B Feb 17 01:08 firebase.json",
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json",
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json",
            "drwxr-xr-x@ 762 salim  staff    24K Feb 25 23:24 node_modules",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 25 23:16 package.json",
            "drwxr-xr-x@   7 salim  staff   224B Feb 17 01:08 packages",
            "-rw-r--r--@   1 salim  staff   500K Feb 25 22:07 pnpm-lock.yaml",
            "-rw-r--r--@   1 salim  staff    27B Feb 17 01:08 pnpm-workspace.yaml",
            "drwxr-xr-x@  17 salim  staff   544B Feb 25 23:24 scripts",
            "-rw-r--r--@   1 salim  staff   513B Feb 17 01:08 tsconfig.base.json",
            "-rw-r--r--@   1 salim  staff   534B Feb 17 01:08 turbo.json"
        )

        repeat(3) {
            for (line in llLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        // Zoom in then zoom out again.
        emulator.resizeAndSync(rows, narrowCols)
        emulator.resizeAndSync(rows, wideCols)

        val snapshot = emulator.getSnapshot()
        val logicalCellLines = extractLogicalCellLines(snapshot.scrollback + snapshot.lines)
        val logicalTrimmed = logicalCellLines.map { trimTrailingPaddingCellsForTest(it) }
        val logicalStrings = logicalTrimmed.map { cellsToRawString(it) }

        for (line in llLines) {
            val matches = logicalTrimmed.filter { cellsToRawString(it) == line }
            assertTrue("Expected line not found after zoom round-trip: '$line'", matches.isNotEmpty())
            for (cells in matches) {
                assertFalse(
                    "Line contains internal NUL cells after zoom round-trip: '$line'",
                    hasInternalNul(cells)
                )
            }
        }
    }

    // ================================================================================
    // Test 35: Tab-aligned columns (ll-like) survive rapid zoom round-trip
    // This matches commands that align via tabs or cursor moves (empty cells).
    // ================================================================================ 

    @Test
    fun testTabAlignedLlOutputSurvivesZoomRoundTrip() = runBlocking {
        val rows = 12
        val wideCols = 90
        val narrowCols = 32
        val emulator = createEmulator(rows = rows, cols = wideCols)

        val llTabLines = listOf(
            "total\t204816",
            "drwxr-xr-x@\t2\tsalim\tstaff\t64B\tFeb\t17\t01:08\t1code",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.3K\tFeb\t17\t01:08\tCONTRIBUTING.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t5.4K\tFeb\t17\t01:08\tINSTALL.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t1.7K\tFeb\t17\t01:08\tREADME.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.8K\tFeb\t17\t01:08\tRELEASING.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t99M\tFeb\t22\t19:01\tapp-production-release.apk",
            "-rw-r--r--@\t1\tsalim\tstaff\t555B\tFeb\t17\t01:08\tcloudbuild.yaml",
            "-rw-r--r--@\t1\tsalim\tstaff\t6.7K\tFeb\t17\t01:08\terrors.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t723B\tFeb\t17\t01:08\tfirebase.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t299K\tFeb\t17\t01:08\ticons-vscode-icons.meta.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t390K\tFeb\t17\t01:08\ticons-vscode-icons.meta.pretty.json",
            "drwxr-xr-x@\t762\tsalim\tstaff\t24K\tFeb\t25\t23:24\tnode_modules",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.8K\tFeb\t25\t23:16\tpackage.json",
            "drwxr-xr-x@\t7\tsalim\tstaff\t224B\tFeb\t17\t01:08\tpackages",
            "-rw-r--r--@\t1\tsalim\tstaff\t500K\tFeb\t25\t22:07\tpnpm-lock.yaml",
            "-rw-r--r--@\t1\tsalim\tstaff\t27B\tFeb\t17\t01:08\tpnpm-workspace.yaml",
            "drwxr-xr-x@\t17\tsalim\tstaff\t544B\tFeb\t25\t23:24\tscripts",
            "-rw-r--r--@\t1\tsalim\tstaff\t513B\tFeb\t17\t01:08\ttsconfig.base.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t534B\tFeb\t17\t01:08\tturbo.json"
        )

        repeat(3) {
            for (line in llTabLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        val before = emulator.getSnapshot()
        val baseline = extractLogicalLines(before.scrollback + before.lines)
        val baselineCounts = baseline.groupingBy { it }.eachCount()

        // Rapid zoom in/out without intermediate sync (simulates pinch)
        emulator.resize(rows, narrowCols)
        emulator.resize(rows, wideCols)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        emulator.processPendingUpdates()

        val after = emulator.getSnapshot()
        val afterLogical = extractLogicalLines(after.scrollback + after.lines)
        val afterCounts = afterLogical.groupingBy { it }.eachCount()

        for ((line, expectedCount) in baselineCounts) {
            val actualCount = afterCounts[line] ?: 0
            assertEquals(
                "Tab-aligned line count mismatch after zoom round-trip: '$line'",
                expectedCount,
                actualCount
            )
        }
    }

    // ================================================================================
    // Test 36: Extreme zoom in/out repeatedly preserves all logical lines each step
    // This is the core regression for "ll" output garbling after multiple zooms.
    // ================================================================================ 

    @Test
    fun testExtremeZoomRepeatsPreserveLogicalLinesEachStep() = runBlocking {
        val rows = 6
        val wideCols = 120
        val emulator = createEmulator(rows = rows, cols = wideCols)

        val llTabLines = listOf(
            "total\t204816",
            "drwxr-xr-x@\t2\tsalim\tstaff\t64B\tFeb\t17\t01:08\t1code",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.3K\tFeb\t17\t01:08\tCONTRIBUTING.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t5.4K\tFeb\t17\t01:08\tINSTALL.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t1.7K\tFeb\t17\t01:08\tREADME.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.8K\tFeb\t17\t01:08\tRELEASING.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t99M\tFeb\t22\t19:01\tapp-production-release.apk",
            "-rw-r--r--@\t1\tsalim\tstaff\t555B\tFeb\t17\t01:08\tcloudbuild.yaml",
            "-rw-r--r--@\t1\tsalim\tstaff\t6.7K\tFeb\t17\t01:08\terrors.md",
            "-rw-r--r--@\t1\tsalim\tstaff\t723B\tFeb\t17\t01:08\tfirebase.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t299K\tFeb\t17\t01:08\ticons-vscode-icons.meta.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t390K\tFeb\t17\t01:08\ticons-vscode-icons.meta.pretty.json",
            "drwxr-xr-x@\t762\tsalim\tstaff\t24K\tFeb\t25\t23:24\tnode_modules",
            "-rw-r--r--@\t1\tsalim\tstaff\t4.8K\tFeb\t25\t23:16\tpackage.json",
            "drwxr-xr-x@\t7\tsalim\tstaff\t224B\tFeb\t17\t01:08\tpackages",
            "-rw-r--r--@\t1\tsalim\tstaff\t500K\tFeb\t25\t22:07\tpnpm-lock.yaml",
            "-rw-r--r--@\t1\tsalim\tstaff\t27B\tFeb\t17\t01:08\tpnpm-workspace.yaml",
            "drwxr-xr-x@\t17\tsalim\tstaff\t544B\tFeb\t25\t23:24\tscripts",
            "-rw-r--r--@\t1\tsalim\tstaff\t513B\tFeb\t17\t01:08\ttsconfig.base.json",
            "-rw-r--r--@\t1\tsalim\tstaff\t534B\tFeb\t17\t01:08\tturbo.json"
        )
        val expectedLines = llTabLines.map { expandTabs(it).trimEnd() }

        repeat(3) {
            for (line in llTabLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        val resizeSteps = listOf(120, 24, 60, 16, 120, 30, 120)
        for (cols in resizeSteps) {
            emulator.resizeAndSync(rows, cols)

            val snapshot = emulator.getSnapshot()
            val logical = extractLogicalLines(snapshot.scrollback + snapshot.lines)
            val logicalCounts = logical.groupingBy { it }.eachCount()

            for (expected in expectedLines) {
                val count = logicalCounts[expected] ?: 0
                assertEquals(
                    "Missing or duplicated logical line after resize to $cols: '$expected'",
                    3,
                    count
                )
            }

            val expectedSet = expectedLines.toSet()
            val unexpectedFragments = logical.filter { line ->
                line.isNotEmpty() &&
                    line !in expectedSet &&
                    expectedLines.any { expected ->
                        expected.length > line.length &&
                            (expected.endsWith(line) || expected.startsWith(line))
                    }
            }
            assertTrue(
                "Found fragmented lines after resize to $cols: $unexpectedFragments",
                unexpectedFragments.isEmpty()
            )
        }
    }

    // ================================================================================
    // Test 37: Row+column zoom round-trip preserves logical lines and spacing
    // Matches: wide terminal, zoom in to ~32 cols (fewer rows), then zoom out.
    // ================================================================================

    @Test
    fun testRowColZoomRoundTripPreservesLlOutput() = runBlocking {
        val wideRows = 24
        val wideCols = 90
        val narrowRows = 12
        val narrowCols = 32
        val emulator = createEmulator(rows = wideRows, cols = wideCols)

        val llLines = listOf(
            "total 204816",
            "drwxr-xr-x@   2 salim  staff    64B Feb 17 01:08 1code",
            "-rw-r--r--@   1 salim  staff   4.3K Feb 17 01:08 CONTRIBUTING.md",
            "-rw-r--r--@   1 salim  staff   5.4K Feb 17 01:08 INSTALL.md",
            "-rw-r--r--@   1 salim  staff   1.7K Feb 17 01:08 README.md",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 17 01:08 RELEASING.md",
            "-rw-r--r--@   1 salim  staff    99M Feb 22 19:01 app-production-release.apk",
            "-rw-r--r--@   1 salim  staff   555B Feb 17 01:08 cloudbuild.yaml",
            "-rw-r--r--@   1 salim  staff   6.7K Feb 17 01:08 errors.md",
            "-rw-r--r--@   1 salim  staff   723B Feb 17 01:08 firebase.json",
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json",
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json",
            "drwxr-xr-x@ 762 salim  staff    24K Feb 25 23:24 node_modules",
            "-rw-r--r--@   1 salim  staff   4.8K Feb 25 23:16 package.json",
            "drwxr-xr-x@   7 salim  staff   224B Feb 17 01:08 packages",
            "-rw-r--r--@   1 salim  staff   500K Feb 25 22:07 pnpm-lock.yaml",
            "-rw-r--r--@   1 salim  staff    27B Feb 17 01:08 pnpm-workspace.yaml",
            "drwxr-xr-x@  17 salim  staff   544B Feb 25 23:24 scripts",
            "-rw-r--r--@   1 salim  staff   513B Feb 17 01:08 tsconfig.base.json",
            "-rw-r--r--@   1 salim  staff   534B Feb 17 01:08 turbo.json"
        )

        repeat(3) {
            for (line in llLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        emulator.resizeAndSync(narrowRows, narrowCols)
        emulator.resizeAndSync(wideRows, wideCols)

        val snapshot = emulator.getSnapshot()
        val logical = snapshot.allLogicalLines()
        val logicalCounts = logical.groupingBy { it }.eachCount()

        for (expected in llLines) {
            val count = logicalCounts[expected] ?: 0
            val related = if (count != 3) {
                logical.filter { it.contains(expected.substringAfterLast(' ')) }
            } else {
                emptyList()
            }
            assertEquals(
                "Missing or duplicated logical line after row+col zoom round-trip: '$expected'. " +
                    "Related=$related",
                3,
                count
            )
        }

        val logicalCellLines = extractLogicalCellLines(snapshot.scrollback + snapshot.lines)
        for (cells in logicalCellLines) {
            assertFalse(
                "Logical line contains internal NUL cells after row+col zoom round-trip: " +
                    "'${cellsToRawString(trimTrailingPaddingCellsForTest(cells))}'",
                hasInternalNul(cells)
            )
        }
    }

    // ================================================================================
    // Test 38: Slow zoom out (row growth + col growth) should unwrap popped lines
    // Repro: lines just above the screen get corrupted when they pop into view.
    // ================================================================================

    @Test
    fun testSlowZoomOutPoppedLinesRemainUnbroken() = runBlocking {
        val narrowRows = 6
        val narrowCols = 32
        val wideRows = 12
        val wideCols = 80
        val emulator = createEmulator(rows = narrowRows, cols = narrowCols)

        val filler = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2) // 72 chars
        val lines = (0 until 12).map { idx ->
            "L%02d $filler".format(idx)
        }

        // Sanity: each line should wrap at 32 but fit in 80.
        for (line in lines) {
            assertTrue("Line too long for wide cols: len=${line.length}", line.length <= wideCols)
            assertTrue("Line too short to wrap at narrow cols: len=${line.length}", line.length > narrowCols * 2)
        }

        // Write enough content to build scrollback.
        repeat(2) {
            for (line in lines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        // Step 1: increase rows only (simulates slow zoom-out; scrollback pops into screen).
        emulator.resizeAndSync(narrowRows + 2, narrowCols)

        // Step 2: increase cols (unwrap should happen for lines that were popped).
        emulator.resizeAndSync(wideRows, wideCols)

        val snapshot = emulator.getSnapshot()
        val allLines = snapshot.scrollback + snapshot.lines

        // The most recent lines (last batch) should exist as SINGLE physical lines.
        val expectedRecent = lines.takeLast(5)
        for (expected in expectedRecent) {
            val idx = allLines.indexOfFirst { it.text.trimEnd() == expected }
            assertTrue("Expected unwrapped line not found: '$expected'", idx >= 0)
            assertFalse(
                "Expected line should not be continuation: '$expected'",
                allLines[idx].continuation
            )
            if (idx + 1 < allLines.size) {
                assertFalse(
                    "Next line should not be continuation of '$expected'",
                    allLines[idx + 1].continuation
                )
            }
        }
    }

    // ================================================================================
    // Test 38b: Slow zoom out should not insert spaces inside unwrapped words
    // Repro: filenames split by wrap gain spaces when popped from scrollback.
    // ================================================================================

    @Test
    fun testSlowZoomOutDoesNotInsertMidWordSpaces() = runBlocking {
        val wideCols = 90
        val narrowCols = 32
        val startRows = 6
        val wideRows = 12
        val emulator = createEmulator(rows = startRows, cols = wideCols)

        val lines = listOf(
            "-rw-r--r--@   1 salim  staff   555B Feb 17 01:08 cloudbuild.yaml",
            "-rw-r--r--@   1 salim  staff   299K Feb 17 01:08 icons-vscode-icons.meta.json",
            "-rw-r--r--@   1 salim  staff   390K Feb 17 01:08 icons-vscode-icons.meta.pretty.json",
            "-rw-r--r--@   1 salim  staff   513B Feb 17 01:08 tsconfig.base.json"
        )

        repeat(4) {
            for (line in lines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        // Narrow to wrap, then grow rows (popping scrollback), then widen.
        emulator.resizeAndSync(startRows, narrowCols)
        emulator.resizeAndSync(startRows + 2, narrowCols)
        emulator.resizeAndSync(wideRows, wideCols)

        val snapshot = emulator.getSnapshot()
        val logical = extractLogicalLines(snapshot.scrollback + snapshot.lines)

        for (line in lines) {
            assertTrue("Expected line after slow zoom-out: '$line'", logical.contains(line))
        }

        val corruptPatterns = listOf(
            Regex("cloudbuild\\.y\\s+aml"),
            Regex("icons-vscode-icons\\.me\\s+ta\\.json"),
            Regex("icons-vscode-icons\\.me\\s+ta\\.pretty\\.json"),
            Regex("tsconfig\\.base\\.js\\s+on")
        )

        val corrupted = logical.filter { line ->
            corruptPatterns.any { it.containsMatchIn(line) } && !lines.contains(line)
        }

        assertTrue(
            "Slow zoom-out should not insert spaces inside wrapped words. Corrupted=$corrupted",
            corrupted.isEmpty()
        )
    }

    // ================================================================================
    // Test 39: Slow zoom out preserves the boundary logical line
    // Repro: line just above the screen breaks after gradual zoom out.
    // ================================================================================

    @Test
    fun testSlowZoomOutPreservesBoundaryLogicalLine() = runBlocking {
        val startRows = 6
        val startCols = 32
        val emulator = createEmulator(rows = startRows, cols = startCols)

        val filler = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2) // 72 chars
        val longLinePrefix = "BOUNDARY"

        fun findLogicalLineAtIndex(
            lines: List<TerminalLine>,
            index: Int
        ): String? {
            var current = StringBuilder()
            var startIdx = 0
            var inLogical = false
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.continuation) {
                    if (inLogical) {
                        val endIdx = i - 1
                        if (index in startIdx..endIdx) {
                            return current.toString().trimEnd()
                        }
                        current = StringBuilder()
                    }
                    inLogical = true
                    startIdx = i
                }
                val isLastInGroup = i + 1 >= lines.size || !lines[i + 1].continuation
                if (isLastInGroup) {
                    current.append(line.text.trimEnd())
                } else {
                    current.append(line.text)
                }
            }
            if (inLogical) {
                val endIdx = lines.size - 1
                if (index in startIdx..endIdx) {
                    return current.toString().trimEnd()
                }
            }
            return null
        }

        // Write long lines until the first screen line is a continuation
        // (meaning the boundary splits a logical line).
        val minWrites = 20
        var attempts = 0
        var snapshot = emulator.getSnapshot()
        while (((snapshot.lines.firstOrNull()?.continuation ?: false) == false || attempts < minWrites) &&
            attempts < 80
        ) {
            val line = "$longLinePrefix-$attempts $filler"
            emulator.writeAndSync("$line\r\n")
            snapshot = emulator.getSnapshot()
            attempts++
        }

        assertTrue(
            "Failed to reach boundary continuation after $attempts writes",
            snapshot.lines.firstOrNull()?.continuation == true
        )

        val preAllLines = snapshot.scrollback + snapshot.lines
        val boundaryIndex = snapshot.scrollback.size
        val boundaryLogical = findLogicalLineAtIndex(preAllLines, boundaryIndex)
        assertTrue("Boundary logical line not found", !boundaryLogical.isNullOrEmpty())

        // Slow zoom-out: increase rows first, then widen columns in steps.
        val resizeSteps = listOf(
            startRows + 2 to startCols,
            startRows + 3 to 36,
            startRows + 4 to 44,
            startRows + 5 to 52,
            startRows + 6 to 80
        )

        for ((rows, cols) in resizeSteps) {
            emulator.resizeAndSync(rows, cols)
            val after = emulator.getSnapshot()
            val logical = extractLogicalLines(after.scrollback + after.lines)
            assertTrue(
                "Boundary logical line lost after resize to ${rows}x${cols}: '$boundaryLogical'. " +
                    "Related=${logical.filter { it.contains(longLinePrefix) }}",
                logical.contains(boundaryLogical)
            )
        }
    }
}
