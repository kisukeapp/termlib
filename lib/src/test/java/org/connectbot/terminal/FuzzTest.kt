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
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Property-based fuzz tests for the Kotlin-side terminal components.
 *
 * These tests feed randomly-generated inputs into parsers, decoders, and
 * search logic to verify that they never crash (no uncaught exceptions,
 * no infinite loops, no out-of-bounds errors) regardless of input.
 *
 * Each test runs a large number of random iterations. Since they use a
 * fixed seed, the results are deterministic and reproducible.
 */
class FuzzTest {

    companion object {
        private const val ITERATIONS = 5_000
        private const val SEED = 0xDEADBEEFL
    }

    // ---- OscParser fuzz ----

    @Test
    fun fuzzOscParserNeverCrashes() {
        val rng = Random(SEED)
        val parser = OscParser()
        val knownCommands = intArrayOf(8, 9, 10, 11, 12, 52, 133, 1337)

        for (i in 0 until ITERATIONS) {
            val command = if (rng.nextBoolean()) {
                knownCommands[rng.nextInt(knownCommands.size)]
            } else {
                rng.nextInt(10000)
            }
            val payload = randomString(rng, rng.nextInt(512))
            val row = rng.nextInt(100)
            val col = rng.nextInt(200)
            val cols = rng.nextInt(300) + 1

            val actions = parser.parse(command, payload, row, col, cols)
            // Must return a valid list (possibly empty)
            assertNotNull("Iteration $i returned null", actions)
        }
    }

    @Test
    fun fuzzOscParserOsc52NeverCrashes() {
        val rng = Random(SEED + 52)
        val parser = OscParser()

        for (i in 0 until ITERATIONS) {
            val selection = when (rng.nextInt(5)) {
                0 -> "c"
                1 -> "p"
                2 -> "s"
                3 -> ""
                else -> randomString(rng, rng.nextInt(10))
            }
            val data = when (rng.nextInt(4)) {
                0 -> "?"  // query (should be rejected)
                1 -> ""   // empty
                2 -> randomBase64(rng, rng.nextInt(256))
                else -> randomString(rng, rng.nextInt(256))
            }
            val payload = "$selection;$data"

            val actions = parser.parse(52, payload, 0, 0, 80)
            assertNotNull(actions)
            // Query should always be rejected
            if (data == "?") {
                assertTrue("OSC 52 query should be empty, iteration $i", actions.isEmpty())
            }
        }
    }

    @Test
    fun fuzzOscParserOsc8NeverCrashes() {
        val rng = Random(SEED + 8)
        val parser = OscParser()

        for (i in 0 until ITERATIONS) {
            val payload = when (rng.nextInt(5)) {
                0 -> ";https://example.com/${randomString(rng, rng.nextInt(100))}"  // start
                1 -> ";"  // end
                2 -> "id=${randomString(rng, 10)};https://example.com"  // with id
                3 -> randomString(rng, rng.nextInt(200))  // garbage
                else -> ";${randomString(rng, rng.nextInt(200))}"
            }
            val row = rng.nextInt(50)
            val col = rng.nextInt(80)

            val actions = parser.parse(8, payload, row, col, 80)
            assertNotNull(actions)
        }
    }

    @Test
    fun fuzzOscParserOsc133NeverCrashes() {
        val rng = Random(SEED + 133)
        val parser = OscParser()
        val validPayloads = arrayOf("A", "B", "C", "D", "D;0", "D;1", "D;127")

        for (i in 0 until ITERATIONS) {
            val payload = if (rng.nextBoolean()) {
                validPayloads[rng.nextInt(validPayloads.size)]
            } else {
                randomString(rng, rng.nextInt(50))
            }
            val row = rng.nextInt(50)
            val col = rng.nextInt(80)

            val actions = parser.parse(133, payload, row, col, 80)
            assertNotNull(actions)
        }
    }

    @Test
    fun fuzzOscParserDynamicColorNeverCrashes() {
        val rng = Random(SEED + 10)
        val parser = OscParser()

        for (i in 0 until ITERATIONS) {
            val command = 10 + rng.nextInt(3) // 10, 11, or 12
            val payload = when (rng.nextInt(6)) {
                0 -> "?"
                1 -> "rgb:${randomHex(rng, 4)}/${randomHex(rng, 4)}/${randomHex(rng, 4)}"
                2 -> "#${randomHex(rng, 6)}"
                3 -> "rgb:${randomString(rng, 20)}"
                4 -> ""
                else -> randomString(rng, rng.nextInt(100))
            }

            val actions = parser.parse(command, payload, 0, 0, 80)
            assertNotNull(actions)
        }
    }

    @Test
    fun fuzzOscParserOsc1337NeverCrashes() {
        val rng = Random(SEED + 1337)
        val parser = OscParser()

        for (i in 0 until ITERATIONS) {
            val payload = when (rng.nextInt(6)) {
                0 -> "AddAnnotation=${randomString(rng, rng.nextInt(200))}"
                1 -> "SetCursorShape=${rng.nextInt(5)}"
                2 -> "File=inline=1;width=${rng.nextInt(100)}:${randomBase64(rng, rng.nextInt(100))}"
                3 -> "File=${randomString(rng, rng.nextInt(50))}:${randomString(rng, rng.nextInt(100))}"
                4 -> "File=inline=0:${randomBase64(rng, 20)}"
                else -> randomString(rng, rng.nextInt(300))
            }

            val actions = parser.parse(1337, payload, rng.nextInt(50), rng.nextInt(80), 80)
            assertNotNull(actions)
        }
    }

    // ---- KittyKeyboardEncoder fuzz ----

    @Test
    fun fuzzKittyKeyboardEncoderNeverCrashes() {
        val rng = Random(SEED + 2048)
        val encoder = KittyKeyboardEncoder()

        for (i in 0 until ITERATIONS) {
            val codepoint = when (rng.nextInt(4)) {
                0 -> rng.nextInt(128)  // ASCII
                1 -> rng.nextInt(0x10FFFF)  // any Unicode
                2 -> VTermKey.ENTER + rng.nextInt(15)  // special keys
                3 -> VTermKey.FUNCTION_0 + rng.nextInt(13)  // function keys
                else -> rng.nextInt(600)
            }
            val modifiers = rng.nextInt(8)  // 0..7 (3 bits: shift/alt/ctrl)
            val eventType = KittyKeyboardEncoder.EventType.entries[rng.nextInt(3)]
            val flags = rng.nextInt(32)  // 0..31 (5 bits)
            val shiftedKey = if (rng.nextBoolean()) rng.nextInt(128) else 0
            val baseLayout = if (rng.nextBoolean()) rng.nextInt(128) else 0

            val result = encoder.encode(codepoint, modifiers, eventType, flags, shiftedKey, baseLayout)
            // result may be null (for keys that shouldn't be encoded) or a valid byte array
            if (result != null) {
                assertTrue("Empty result at iteration $i", result.isNotEmpty())
                // Must start with ESC [
                assertEquals("Result must start with ESC at iteration $i", 0x1b, result[0].toInt())
            }
        }
    }

    @Test
    fun fuzzKittyKeyboardEncoderKeypadKeys() {
        val rng = Random(SEED + 512)
        val encoder = KittyKeyboardEncoder()

        for (i in 0 until ITERATIONS) {
            val vtermKey = VTermKey.KP_0 + rng.nextInt(18)  // KP_0 through KP_EQUAL
            val modifiers = rng.nextInt(8)
            val eventType = KittyKeyboardEncoder.EventType.entries[rng.nextInt(3)]
            val flags = KittyKeyboardFlags.DISAMBIGUATE or
                    (if (rng.nextBoolean()) KittyKeyboardFlags.REPORT_EVENTS else 0)

            val result = encoder.encode(vtermKey, modifiers, eventType, flags)
            // With DISAMBIGUATE, all keys should produce output
            assertNotNull("Keypad key should encode at iteration $i", result)
        }
    }

    // ---- SearchEngine fuzz ----

    @Test
    fun fuzzSearchEngineNeverCrashes() {
        val rng = Random(SEED + 999)
        val engine = SearchEngine()

        for (i in 0 until ITERATIONS) {
            val query = randomString(rng, rng.nextInt(50) + 1)
            val options = SearchOptions(
                caseSensitive = rng.nextBoolean(),
                regex = false,  // avoid invalid regex exceptions
                wholeWord = rng.nextBoolean(),
                wrapAround = rng.nextBoolean()
            )

            val numLines = rng.nextInt(20) + 1
            val numCols = rng.nextInt(100) + 10
            val lines = (0 until numLines).map { row ->
                randomTerminalLine(rng, row, numCols)
            }
            val scrollback = if (rng.nextBoolean()) {
                val numScrollback = rng.nextInt(10)
                (0 until numScrollback).map { row ->
                    randomTerminalLine(rng, -(row + 1), numCols)
                }
            } else {
                emptyList()
            }

            val results = engine.search(query, options, lines, scrollback)
            assertNotNull(results)

            // Test findNext/findPrevious
            if (results.isNotEmpty()) {
                val next = engine.findNext(0, results.size, options.wrapAround)
                assertTrue("findNext out of range", next < results.size)
                val prev = engine.findPrevious(0, results.size, options.wrapAround)
                assertTrue("findPrevious out of range", prev < results.size)
            }
        }
    }

    @Test
    fun fuzzSearchEngineWithRegexNeverCrashes() {
        val rng = Random(SEED + 998)
        val engine = SearchEngine()
        // Some patterns that might cause catastrophic backtracking or parse errors
        val regexPatterns = arrayOf(
            ".*", ".+", "\\d+", "[a-z]+", "\\w+", "^test", "end$",
            "(a|b|c)", "\\s+", "[^x]", "a{1,3}", "(?:foo|bar)",
            "\\bword\\b", ".", ""
        )

        for (i in 0 until ITERATIONS) {
            val query = if (rng.nextBoolean()) {
                regexPatterns[rng.nextInt(regexPatterns.size)]
            } else {
                // Simple safe patterns
                randomAlphanumericString(rng, rng.nextInt(10) + 1)
            }
            val options = SearchOptions(
                caseSensitive = rng.nextBoolean(),
                regex = true,
                wholeWord = false,
                wrapAround = rng.nextBoolean()
            )

            val lines = (0 until 5).map { row ->
                randomTerminalLine(rng, row, 80)
            }

            try {
                val results = engine.search(query, options, lines, emptyList())
                assertNotNull(results)
            } catch (e: java.util.regex.PatternSyntaxException) {
                // Regex compilation errors are acceptable for random patterns
            }
        }
    }

    @Test
    fun fuzzSearchEngineFindNextPrevious() {
        val rng = Random(SEED + 997)
        val engine = SearchEngine()

        for (i in 0 until ITERATIONS) {
            val total = rng.nextInt(100)
            val current = if (total > 0) rng.nextInt(total) else -1
            val wrap = rng.nextBoolean()

            val next = engine.findNext(current, total, wrap)
            val prev = engine.findPrevious(current, total, wrap)

            if (total <= 0) {
                assertEquals(-1, next)
                assertEquals(-1, prev)
            } else {
                assertTrue("findNext result $next out of valid range for total $total",
                    next in -1 until total)
                assertTrue("findPrevious result $prev out of valid range for total $total",
                    prev in -1 until total)
            }
        }
    }

    // ---- ImplicitLinkDetector fuzz ----

    @Test
    fun fuzzImplicitLinkDetectorNeverCrashes() {
        val rng = Random(SEED + 777)
        val detector = ImplicitLinkDetector()

        for (i in 0 until ITERATIONS) {
            val cols = rng.nextInt(200) + 10
            val line = randomTerminalLine(rng, 0, cols)

            val links = detector.detectLinks(line)
            assertNotNull(links)

            // All links should have valid column ranges
            for (link in links) {
                assertTrue("startCol negative: ${link.startCol}", link.startCol >= 0)
                assertTrue("endCol < startCol", link.endCol >= link.startCol)
                assertFalse("URL empty", link.url.isEmpty())
            }

            // detectLinkAt should never crash
            val col = rng.nextInt(cols)
            detector.detectLinkAt(line, col) // may return null, that's fine
        }
    }

    @Test
    fun fuzzImplicitLinkDetectorWithUrls() {
        val rng = Random(SEED + 776)
        val detector = ImplicitLinkDetector()
        val protocols = arrayOf("https://", "http://", "ftp://", "ssh://", "file://")

        for (i in 0 until ITERATIONS) {
            val cols = 120
            val cells = mutableListOf<TerminalLine.Cell>()
            // Inject a URL somewhere in the line
            val prefix = randomAlphanumericString(rng, rng.nextInt(20))
            val protocol = protocols[rng.nextInt(protocols.size)]
            val domain = randomAlphanumericString(rng, rng.nextInt(30) + 3)
            val path = "/${randomAlphanumericString(rng, rng.nextInt(20))}"
            val url = "$protocol$domain$path"
            val text = "$prefix $url ${randomAlphanumericString(rng, rng.nextInt(20))}"

            for (ch in text) {
                cells.add(TerminalLine.Cell(
                    char = ch,
                    fgColor = Color.White,
                    bgColor = Color.Black
                ))
            }
            // Pad to cols
            while (cells.size < cols) {
                cells.add(TerminalLine.Cell(char = '\u0000', fgColor = Color.White, bgColor = Color.Black))
            }

            val line = TerminalLine(row = 0, cells = cells.take(cols))
            val links = detector.detectLinks(line)
            assertNotNull(links)
            // Should find at least the URL we injected (unless it was malformed)
            if (domain.isNotEmpty()) {
                assertTrue("Should detect at least one link in: $text, iteration $i",
                    links.isNotEmpty())
            }
        }
    }

    // Note: ImageManager fuzz testing requires android.graphics.Bitmap and must
    // be run as an instrumentation test (androidTest), not a local JVM unit test.

    // ---- Random ANSI sequence generator ----

    @Test
    fun fuzzOscParserWithAnsiSequences() {
        val rng = Random(SEED + 42)
        val parser = OscParser()

        for (i in 0 until ITERATIONS) {
            // Generate a random ANSI-like payload
            val payload = randomAnsiPayload(rng)
            val command = rng.nextInt(2000)

            val actions = parser.parse(command, payload, rng.nextInt(50), rng.nextInt(80), 80)
            assertNotNull(actions)
        }
    }

    // ---- Helper functions ----

    private fun randomString(rng: Random, length: Int): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length)
        for (j in 0 until length) {
            sb.append(rng.nextInt(0x10000).toChar())
        }
        return sb.toString()
    }

    private fun randomAlphanumericString(rng: Random, length: Int): String {
        if (length <= 0) return ""
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sb = StringBuilder(length)
        for (j in 0 until length) {
            sb.append(chars[rng.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun randomBase64(rng: Random, length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(length)
        for (j in 0 until length) {
            sb.append(chars[rng.nextInt(chars.length)])
        }
        // Add padding
        while (sb.length % 4 != 0) {
            sb.append('=')
        }
        return sb.toString()
    }

    private fun randomHex(rng: Random, length: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(length)
        for (j in 0 until length) {
            sb.append(chars[rng.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun randomTerminalLine(rng: Random, row: Int, cols: Int): TerminalLine {
        val cells = (0 until cols).map {
            val char = when (rng.nextInt(10)) {
                0 -> '\u0000'  // empty
                1 -> ' '       // space
                in 2..7 -> ('a' + rng.nextInt(26))  // letters
                8 -> ('0' + rng.nextInt(10))  // digits
                else -> rng.nextInt(0x7F).toChar()  // printable ASCII
            }
            TerminalLine.Cell(
                char = char,
                fgColor = Color.White,
                bgColor = Color.Black,
                width = if (rng.nextInt(20) == 0) 2 else 1
            )
        }
        return TerminalLine(
            row = row,
            cells = cells,
            continuation = rng.nextInt(5) == 0
        )
    }

    private fun randomAnsiPayload(rng: Random): String {
        val sb = StringBuilder()
        val length = rng.nextInt(200)
        for (j in 0 until length) {
            when (rng.nextInt(10)) {
                0 -> sb.append('\u001b')    // ESC
                1 -> sb.append('[')          // CSI start
                2 -> sb.append(';')          // separator
                3 -> sb.append(rng.nextInt(256).toChar())
                4 -> sb.append(('0' + rng.nextInt(10)))  // digit
                5 -> sb.append(('A' + rng.nextInt(26)))  // uppercase letter
                6 -> sb.append('?')
                7 -> sb.append('#')
                else -> sb.append((' ' + rng.nextInt(95)).toChar())  // printable ASCII
            }
        }
        return sb.toString()
    }
}
