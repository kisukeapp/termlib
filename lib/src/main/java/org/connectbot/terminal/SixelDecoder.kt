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

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Decodes Sixel graphics data into Android [Bitmap] instances.
 *
 * Sixel is a bitmap graphics format used in terminal emulators. Each sixel character
 * encodes a column of 6 vertical pixels. The data stream includes palette definitions,
 * repeat counts, and positioning commands (carriage return and line feed).
 *
 * This decoder uses a two-pass approach:
 * 1. A size pass to compute the required width and height without rendering.
 * 2. A render pass to create an ARGB_8888 bitmap and fill in pixel data.
 */
internal class SixelDecoder {

    companion object {
        /** The number of vertical pixels encoded by a single sixel character. */
        private const val SIXEL_HEIGHT = 6

        /** First valid sixel data character. */
        private const val SIXEL_CHAR_START = 0x3F

        /** Last valid sixel data character. */
        private const val SIXEL_CHAR_END = 0x7E

        /** Default VGA 16-color palette. */
        private val DEFAULT_PALETTE = intArrayOf(
            Color.rgb(0, 0, 0),       // 0: Black
            Color.rgb(0, 0, 205),     // 1: Blue
            Color.rgb(205, 0, 0),     // 2: Red
            Color.rgb(0, 205, 0),     // 3: Green
            Color.rgb(205, 0, 205),   // 4: Magenta
            Color.rgb(0, 205, 205),   // 5: Cyan
            Color.rgb(205, 205, 0),   // 6: Yellow
            Color.rgb(250, 250, 250), // 7: White
            Color.rgb(85, 85, 85),    // 8: Bright Black (Gray)
            Color.rgb(0, 0, 255),     // 9: Bright Blue
            Color.rgb(255, 0, 0),     // 10: Bright Red
            Color.rgb(0, 255, 0),     // 11: Bright Green
            Color.rgb(255, 0, 255),   // 12: Bright Magenta
            Color.rgb(0, 255, 255),   // 13: Bright Cyan
            Color.rgb(255, 255, 0),   // 14: Bright Yellow
            Color.rgb(255, 255, 255), // 15: Bright White
        )
    }

    /**
     * Decodes a sixel data string into a [Bitmap].
     *
     * @param data The sixel data string (the content between `q` and `ST` in the DCS sequence).
     * @return An ARGB_8888 [Bitmap] containing the rendered image, or `null` if the data
     *         is empty or invalid.
     */
    fun decode(data: String): Bitmap? {
        if (data.isEmpty()) return null

        // Size pass: determine dimensions
        val (width, height) = computeSize(data) ?: return null
        if (width <= 0 || height <= 0) return null

        // Render pass: create bitmap and fill pixels
        return renderBitmap(data, width, height)
    }

    /**
     * Scans the sixel data to compute the required image dimensions without rendering.
     *
     * @return A [Pair] of (width, height), or `null` if the data contains no renderable content.
     */
    private fun computeSize(data: String): Pair<Int, Int>? {
        var x = 0
        var y = 0
        var maxX = 0
        var i = 0

        while (i < data.length) {
            val c = data[i]
            when {
                c == '#' -> {
                    // Skip palette definition or color selection
                    i++
                    i = skipPastSemicolonsAndDigits(data, i)
                    continue
                }
                c == '!' -> {
                    // Repeat: !count char
                    i++
                    val (count, nextIndex) = parseNumber(data, i)
                    i = nextIndex
                    if (i < data.length) {
                        val ch = data[i]
                        if (ch.code in SIXEL_CHAR_START..SIXEL_CHAR_END) {
                            x += count
                            if (x > maxX) maxX = x
                        }
                        i++
                    }
                    continue
                }
                c == '$' -> {
                    // Carriage return: move to start of current row
                    x = 0
                }
                c == '-' -> {
                    // Line feed: move down 6 pixels
                    x = 0
                    y += SIXEL_HEIGHT
                }
                c.code in SIXEL_CHAR_START..SIXEL_CHAR_END -> {
                    x++
                    if (x > maxX) maxX = x
                }
            }
            i++
        }

        val totalHeight = y + SIXEL_HEIGHT
        return if (maxX > 0) Pair(maxX, totalHeight) else null
    }

    /**
     * Creates a bitmap and renders the sixel data into it.
     */
    private fun renderBitmap(data: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Initialize palette with defaults
        val palette = HashMap<Int, Int>()
        for (index in DEFAULT_PALETTE.indices) {
            palette[index] = DEFAULT_PALETTE[index]
        }

        var currentColor = palette[0] ?: Color.BLACK
        var x = 0
        var y = 0
        var i = 0

        while (i < data.length) {
            val c = data[i]
            when {
                c == '#' -> {
                    i++
                    val (colorIndex, nextIndex) = parseNumber(data, i)
                    i = nextIndex
                    if (i < data.length && data[i] == ';') {
                        // Palette definition: #Pc;Pu;Px;Py;Pz
                        i++ // skip ';'
                        val (colorSystem, idx2) = parseNumber(data, i)
                        i = idx2
                        if (i < data.length && data[i] == ';') {
                            i++
                            val (v1, idx3) = parseNumber(data, i)
                            i = idx3
                            if (i < data.length && data[i] == ';') {
                                i++
                                val (v2, idx4) = parseNumber(data, i)
                                i = idx4
                                if (i < data.length && data[i] == ';') {
                                    i++
                                    val (v3, idx5) = parseNumber(data, i)
                                    i = idx5
                                    val rgb = when (colorSystem) {
                                        1 -> hlsToRgb(v1, v2, v3)
                                        2 -> Color.rgb(
                                            (v1 * 255 / 100).coerceIn(0, 255),
                                            (v2 * 255 / 100).coerceIn(0, 255),
                                            (v3 * 255 / 100).coerceIn(0, 255),
                                        )
                                        else -> Color.BLACK
                                    }
                                    palette[colorIndex] = rgb
                                    currentColor = rgb
                                }
                            }
                        }
                    } else {
                        // Color selection only: #Pc
                        currentColor = palette[colorIndex] ?: Color.BLACK
                    }
                    continue
                }
                c == '!' -> {
                    // Repeat: !count char
                    i++
                    val (count, nextIndex) = parseNumber(data, i)
                    i = nextIndex
                    if (i < data.length) {
                        val ch = data[i]
                        if (ch.code in SIXEL_CHAR_START..SIXEL_CHAR_END) {
                            val bits = ch.code - SIXEL_CHAR_START
                            for (r in 0 until count) {
                                drawSixel(bitmap, x, y, bits, currentColor, width, height)
                                x++
                            }
                        }
                        i++
                    }
                    continue
                }
                c == '$' -> {
                    // Carriage return
                    x = 0
                }
                c == '-' -> {
                    // Line feed
                    x = 0
                    y += SIXEL_HEIGHT
                }
                c.code in SIXEL_CHAR_START..SIXEL_CHAR_END -> {
                    val bits = c.code - SIXEL_CHAR_START
                    drawSixel(bitmap, x, y, bits, currentColor, width, height)
                    x++
                }
            }
            i++
        }

        return bitmap
    }

    /**
     * Draws a single sixel column (6 vertical pixels) into the bitmap.
     *
     * @param bitmap The target bitmap.
     * @param x The horizontal pixel position.
     * @param y The vertical pixel position of the top of the 6-pixel column.
     * @param bits The 6-bit pattern (LSB = top pixel).
     * @param color The ARGB color to use for set bits.
     * @param width The bitmap width (for bounds checking).
     * @param height The bitmap height (for bounds checking).
     */
    private fun drawSixel(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        bits: Int,
        color: Int,
        width: Int,
        height: Int,
    ) {
        if (x < 0 || x >= width) return
        for (bit in 0 until SIXEL_HEIGHT) {
            if (bits and (1 shl bit) != 0) {
                val py = y + bit
                if (py in 0 until height) {
                    bitmap.setPixel(x, py, color)
                }
            }
        }
    }

    /**
     * Parses an integer from the data string starting at the given index.
     *
     * @return A [Pair] of (parsed value, next index after the number).
     */
    private fun parseNumber(data: String, startIndex: Int): Pair<Int, Int> {
        var i = startIndex
        var value = 0
        while (i < data.length && data[i].isDigit()) {
            value = value * 10 + (data[i] - '0')
            i++
        }
        return Pair(value, i)
    }

    /**
     * Skips past a sequence of digits and semicolons, used for scanning past
     * palette definitions during the size pass.
     */
    private fun skipPastSemicolonsAndDigits(data: String, startIndex: Int): Int {
        var i = startIndex
        while (i < data.length && (data[i].isDigit() || data[i] == ';')) {
            i++
        }
        return i
    }

    /**
     * Converts HLS (Hue, Lightness, Saturation) color values to an ARGB color integer.
     *
     * The sixel color system uses:
     * - H: Hue angle in degrees (0-360)
     * - L: Lightness percentage (0-100)
     * - S: Saturation percentage (0-100)
     *
     * @param h Hue angle (0-360).
     * @param l Lightness percentage (0-100).
     * @param s Saturation percentage (0-100).
     * @return An ARGB color integer.
     */
    private fun hlsToRgb(h: Int, l: Int, s: Int): Int {
        val lightness = l / 100.0
        val saturation = s / 100.0

        if (saturation == 0.0) {
            val gray = (lightness * 255).toInt().coerceIn(0, 255)
            return Color.rgb(gray, gray, gray)
        }

        val q = if (lightness < 0.5) {
            lightness * (1.0 + saturation)
        } else {
            lightness + saturation - lightness * saturation
        }
        val p = 2.0 * lightness - q
        val hNorm = h / 360.0

        val r = hueToChannel(p, q, hNorm + 1.0 / 3.0)
        val g = hueToChannel(p, q, hNorm)
        val b = hueToChannel(p, q, hNorm - 1.0 / 3.0)

        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255),
        )
    }

    /**
     * Helper for HLS-to-RGB conversion. Computes a single color channel value
     * from the intermediate values p, q, and an adjusted hue.
     */
    private fun hueToChannel(p: Double, q: Double, hueIn: Double): Double {
        var t = hueIn
        if (t < 0.0) t += 1.0
        if (t > 1.0) t -= 1.0
        return when {
            t < 1.0 / 6.0 -> p + (q - p) * 6.0 * t
            t < 1.0 / 2.0 -> q
            t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6.0
            else -> p
        }
    }
}
