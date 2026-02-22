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

        for (line in lines) {
            if (!line.continuation) {
                if (inLogical) {
                    result.add(current.toString().trimEnd())
                    current = StringBuilder()
                }
                inLogical = true
            }
            current.append(line.text.trimEnd())
        }
        if (inLogical) {
            result.add(current.toString().trimEnd())
        }
        return result.filter { it.isNotEmpty() }
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
}
