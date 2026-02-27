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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Renders Unicode box drawing characters (U+2500-U+257F) using Compose Canvas drawing
 * primitives instead of text rendering, producing pixel-perfect results regardless of
 * font metrics.
 *
 * Each character is decomposed into line segments defined as fractions of the cell
 * dimensions, with a weight that controls stroke thickness.
 */
internal object BoxDrawingRenderer {

    private const val BOX_DRAWING_START = 0x2500
    private const val BOX_DRAWING_END = 0x257F

    /** Stroke weight multipliers applied to a base stroke width derived from cell size. */
    private const val LIGHT = 1f
    private const val HEAVY = 2.5f

    /** Offset from center for double-line variants, as a fraction of cell dimension. */
    private const val DOUBLE_OFFSET = 0.15f

    /**
     * Returns true if [codepoint] is a Unicode box drawing character (U+2500-U+257F).
     */
    fun isBoxDrawingChar(codepoint: Int): Boolean =
        codepoint in BOX_DRAWING_START..BOX_DRAWING_END

    /**
     * A single line segment within a cell, expressed as fractions (0.0-1.0) of the cell
     * width and height.
     */
    private data class Segment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val weight: Float = LIGHT,
    )

    /**
     * Descriptor for a box drawing character: either a list of line [segments] or a
     * special shape (arc / diagonal) identified by [special].
     */
    private data class BoxChar(
        val segments: List<Segment> = emptyList(),
        val special: Special? = null,
    )

    private enum class Special {
        ARC_DOWN_RIGHT,   // ╭  U+256D
        ARC_DOWN_LEFT,    // ╮  U+256E
        ARC_UP_LEFT,      // ╯  U+256F
        ARC_UP_RIGHT,     // ╰  U+2570
        DIAGONAL_UPPER_RIGHT_TO_LOWER_LEFT,  // ╱  U+2571
        DIAGONAL_UPPER_LEFT_TO_LOWER_RIGHT,  // ╲  U+2572
        DIAGONAL_CROSS,   // ╳  U+2573
    }

    // ----- helpers to build segment lists concisely -----

    /** Light horizontal: left edge to right edge at vertical center. */
    private val LH = Segment(0f, 0.5f, 1f, 0.5f, LIGHT)

    /** Light vertical: top edge to bottom edge at horizontal center. */
    private val LV = Segment(0.5f, 0f, 0.5f, 1f, LIGHT)

    /** Light half-segments from center outward. */
    private val LR = Segment(0.5f, 0.5f, 1f, 0.5f, LIGHT)   // center → right
    private val LL = Segment(0f, 0.5f, 0.5f, 0.5f, LIGHT)    // left → center
    private val LD = Segment(0.5f, 0.5f, 0.5f, 1f, LIGHT)    // center → down
    private val LU = Segment(0.5f, 0f, 0.5f, 0.5f, LIGHT)    // up → center

    /** Heavy full lines. */
    private val HH = Segment(0f, 0.5f, 1f, 0.5f, HEAVY)
    private val HV = Segment(0.5f, 0f, 0.5f, 1f, HEAVY)

    /** Heavy half-segments from center outward. */
    private val HR = Segment(0.5f, 0.5f, 1f, 0.5f, HEAVY)
    private val HL = Segment(0f, 0.5f, 0.5f, 0.5f, HEAVY)
    private val HD = Segment(0.5f, 0.5f, 0.5f, 1f, HEAVY)
    private val HU = Segment(0.5f, 0f, 0.5f, 0.5f, HEAVY)

    // ----- double-line helpers (two parallel lines offset from center) -----

    private val D = DOUBLE_OFFSET

    private val DH = listOf(
        Segment(0f, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 1f, 0.5f + D, LIGHT),
    )
    private val DV = listOf(
        Segment(0.5f - D, 0f, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 1f, LIGHT),
    )

    // Double half-segments from center outward
    private val DR = listOf(
        Segment(0.5f, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f, 0.5f + D, 1f, 0.5f + D, LIGHT),
    )
    private val DL = listOf(
        Segment(0f, 0.5f - D, 0.5f, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f, 0.5f + D, LIGHT),
    )
    private val DD = listOf(
        Segment(0.5f - D, 0.5f, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0.5f, 0.5f + D, 1f, LIGHT),
    )
    private val DU = listOf(
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f, LIGHT),
    )

    // Double corner connectors: the inner corner line that bridges the two parallel tracks.
    // For double down-right (╔), the horizontal pair comes from center-right AND
    // the vertical pair comes from center-down, with small bridging segments at the corner.
    private fun doubleDownRight(): List<Segment> = listOf(
        // outer horizontal: center → right at upper track
        Segment(0.5f + D, 0.5f - D, 1f, 0.5f - D, LIGHT),
        // inner horizontal: center → right at lower track
        Segment(0.5f - D, 0.5f + D, 1f, 0.5f + D, LIGHT),
        // outer vertical: center → down at left track
        Segment(0.5f - D, 0.5f + D, 0.5f - D, 1f, LIGHT),
        // inner vertical: center → down at right track
        Segment(0.5f + D, 0.5f - D, 0.5f + D, 1f, LIGHT),
    )

    private fun doubleDownLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f - D, 0.5f - D, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f + D, 0.5f + D, LIGHT),
        Segment(0.5f + D, 0.5f + D, 0.5f + D, 1f, LIGHT),
        Segment(0.5f - D, 0.5f - D, 0.5f - D, 1f, LIGHT),
    )

    private fun doubleUpRight(): List<Segment> = listOf(
        Segment(0.5f + D, 0.5f + D, 1f, 0.5f + D, LIGHT),
        Segment(0.5f - D, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f - D, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f + D, LIGHT),
    )

    private fun doubleUpLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f + D, 0.5f - D, 0.5f + D, LIGHT),
        Segment(0f, 0.5f - D, 0.5f + D, 0.5f - D, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f - D, LIGHT),
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f + D, LIGHT),
    )

    private fun doubleVerticalRight(): List<Segment> = DV + listOf(
        Segment(0.5f + D, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f + D, 0.5f + D, 1f, 0.5f + D, LIGHT),
    )

    private fun doubleVerticalLeft(): List<Segment> = DV + listOf(
        Segment(0f, 0.5f - D, 0.5f - D, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f - D, 0.5f + D, LIGHT),
    )

    private fun doubleHorizontalDown(): List<Segment> = DH + listOf(
        Segment(0.5f - D, 0.5f + D, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0.5f + D, 0.5f + D, 1f, LIGHT),
    )

    private fun doubleHorizontalUp(): List<Segment> = DH + listOf(
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f - D, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f - D, LIGHT),
    )

    private fun doubleCross(): List<Segment> = DH + DV

    // ----- Mixed double/single helpers -----

    // Single horizontal with double vertical tee-right (╟)
    private fun singleVDoubleRight(): List<Segment> = DV + listOf(
        Segment(0.5f + D, 0.5f, 1f, 0.5f, LIGHT),
    )

    // Single horizontal with double vertical tee-left (╢)
    private fun singleVDoubleLeft(): List<Segment> = DV + listOf(
        Segment(0f, 0.5f, 0.5f - D, 0.5f, LIGHT),
    )

    // Double horizontal with single vertical tee-right (╞)
    private fun doubleHSingleRight(): List<Segment> = listOf(
        Segment(0.5f, 0f, 0.5f, 1f, LIGHT),
        Segment(0.5f, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f, 0.5f + D, 1f, 0.5f + D, LIGHT),
    )

    // Double horizontal with single vertical tee-left (╡)
    private fun doubleHSingleLeft(): List<Segment> = listOf(
        Segment(0.5f, 0f, 0.5f, 1f, LIGHT),
        Segment(0f, 0.5f - D, 0.5f, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f, 0.5f + D, LIGHT),
    )

    // Single vertical with double horizontal tee-down (╥)
    private fun doubleHSingleDown(): List<Segment> = DH + listOf(
        Segment(0.5f, 0.5f + D, 0.5f, 1f, LIGHT),
    )

    // Single vertical with double horizontal tee-up (╨)
    private fun doubleHSingleUp(): List<Segment> = DH + listOf(
        Segment(0.5f, 0f, 0.5f, 0.5f - D, LIGHT),
    )

    // Double vertical with single horizontal tee-down (╤)
    private fun singleHDoubleDown(): List<Segment> = listOf(
        Segment(0f, 0.5f, 1f, 0.5f, LIGHT),
        Segment(0.5f - D, 0.5f, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0.5f, 0.5f + D, 1f, LIGHT),
    )

    // Double vertical with single horizontal tee-up (╧)
    private fun singleHDoubleUp(): List<Segment> = listOf(
        Segment(0f, 0.5f, 1f, 0.5f, LIGHT),
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f, LIGHT),
    )

    // Double horizontal + single vertical cross (╪)
    private fun doubleHSingleVCross(): List<Segment> = DH + listOf(LV)

    // Single horizontal + double vertical cross (╫)
    private fun singleHDoubleVCross(): List<Segment> = DV + listOf(LH)

    // Mixed corners: double horizontal + single vertical
    private fun doubleHSingleVDownRight(): List<Segment> = listOf(
        Segment(0.5f, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f, 0.5f + D, 1f, 0.5f + D, LIGHT),
        Segment(0.5f, 0.5f + D, 0.5f, 1f, LIGHT),
    )

    private fun doubleHSingleVDownLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f - D, 0.5f, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f, 0.5f + D, LIGHT),
        Segment(0.5f, 0.5f + D, 0.5f, 1f, LIGHT),
    )

    private fun doubleHSingleVUpRight(): List<Segment> = listOf(
        Segment(0.5f, 0.5f - D, 1f, 0.5f - D, LIGHT),
        Segment(0.5f, 0.5f + D, 1f, 0.5f + D, LIGHT),
        Segment(0.5f, 0f, 0.5f, 0.5f - D, LIGHT),
    )

    private fun doubleHSingleVUpLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f - D, 0.5f, 0.5f - D, LIGHT),
        Segment(0f, 0.5f + D, 0.5f, 0.5f + D, LIGHT),
        Segment(0.5f, 0f, 0.5f, 0.5f - D, LIGHT),
    )

    // Mixed corners: single horizontal + double vertical
    private fun singleHDoubleVDownRight(): List<Segment> = listOf(
        Segment(0.5f + D, 0.5f, 1f, 0.5f, LIGHT),
        Segment(0.5f - D, 0.5f, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0.5f, 0.5f + D, 1f, LIGHT),
    )

    private fun singleHDoubleVDownLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f, 0.5f - D, 0.5f, LIGHT),
        Segment(0.5f - D, 0.5f, 0.5f - D, 1f, LIGHT),
        Segment(0.5f + D, 0.5f, 0.5f + D, 1f, LIGHT),
    )

    private fun singleHDoubleVUpRight(): List<Segment> = listOf(
        Segment(0.5f + D, 0.5f, 1f, 0.5f, LIGHT),
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f, LIGHT),
    )

    private fun singleHDoubleVUpLeft(): List<Segment> = listOf(
        Segment(0f, 0.5f, 0.5f + D, 0.5f, LIGHT),
        Segment(0.5f - D, 0f, 0.5f - D, 0.5f, LIGHT),
        Segment(0.5f + D, 0f, 0.5f + D, 0.5f, LIGHT),
    )

    /**
     * Lookup table indexed by (codepoint - 0x2500).
     * null entries fall back to no rendering (the caller should use text fallback).
     */
    private val TABLE: Array<BoxChar?> = arrayOfNulls<BoxChar>(128).also { t ->
        fun set(cp: Int, vararg segs: Segment) {
            t[cp - BOX_DRAWING_START] = BoxChar(segments = segs.toList())
        }
        fun setList(cp: Int, segs: List<Segment>) {
            t[cp - BOX_DRAWING_START] = BoxChar(segments = segs)
        }
        fun setSpecial(cp: Int, special: Special) {
            t[cp - BOX_DRAWING_START] = BoxChar(special = special)
        }

        // ── Light lines ──────────────────────────────────────────────
        set(0x2500, LH)            // ─  light horizontal
        set(0x2501, HH)            // ━  heavy horizontal
        set(0x2502, LV)            // │  light vertical
        set(0x2503, HV)            // ┃  heavy vertical

        // Light triple-dash / quadruple-dash (rendered as simple lines)
        set(0x2504, LH)            // ┄  light triple dash horizontal
        set(0x2505, HH)            // ┅  heavy triple dash horizontal
        set(0x2506, LV)            // ┆  light triple dash vertical
        set(0x2507, HV)            // ┇  heavy triple dash vertical
        set(0x2508, LH)            // ┈  light quadruple dash horizontal
        set(0x2509, HH)            // ┉  heavy quadruple dash horizontal
        set(0x250A, LV)            // ┊  light quadruple dash vertical
        set(0x250B, HV)            // ┋  heavy quadruple dash vertical

        // ── Light corners ────────────────────────────────────────────
        set(0x250C, LR, LD)        // ┌  light down and right
        set(0x250D, HR, LD)        // ┍  down light and right heavy
        set(0x250E, LR, HD)        // ┎  down heavy and right light
        set(0x250F, HR, HD)        // ┏  heavy down and right

        set(0x2510, LL, LD)        // ┐  light down and left
        set(0x2511, HL, LD)        // ┑  down light and left heavy
        set(0x2512, LL, HD)        // ┒  down heavy and left light
        set(0x2513, HL, HD)        // ┓  heavy down and left

        set(0x2514, LR, LU)        // └  light up and right
        set(0x2515, HR, LU)        // ┕  up light and right heavy
        set(0x2516, LR, HU)        // ┖  up heavy and right light
        set(0x2517, HR, HU)        // ┗  heavy up and right

        set(0x2518, LL, LU)        // ┘  light up and left
        set(0x2519, HL, LU)        // ┙  up light and left heavy
        set(0x251A, LL, HU)        // ┚  up heavy and left light
        set(0x251B, HL, HU)        // ┛  heavy up and left

        // ── T-pieces (tees) ─────────────────────────────────────────
        set(0x251C, LR, LU, LD)    // ├  light vertical and right
        set(0x251D, HR, LU, LD)    // ┝  vertical light and right heavy
        set(0x251E, LR, HU, LD)    // ┞  up heavy and right down light
        set(0x251F, LR, LU, HD)    // ┟  down heavy and right up light
        set(0x2520, LR, HU, HD)    // ┠  vertical heavy and right light
        set(0x2521, HR, HU, LD)    // ┡  down light and right up heavy
        set(0x2522, HR, LU, HD)    // ┢  up light and right down heavy
        set(0x2523, HR, HU, HD)    // ┣  heavy vertical and right

        set(0x2524, LL, LU, LD)    // ┤  light vertical and left
        set(0x2525, HL, LU, LD)    // ┥  vertical light and left heavy
        set(0x2526, LL, HU, LD)    // ┦  up heavy and left down light
        set(0x2527, LL, LU, HD)    // ┧  down heavy and left up light
        set(0x2528, LL, HU, HD)    // ┨  vertical heavy and left light
        set(0x2529, HL, HU, LD)    // ┩  down light and left up heavy
        set(0x252A, HL, LU, HD)    // ┪  up light and left down heavy
        set(0x252B, HL, HU, HD)    // ┫  heavy vertical and left

        set(0x252C, LL, LR, LD)    // ┬  light down and horizontal
        set(0x252D, HL, LR, LD)    // ┭  left heavy and right down light
        set(0x252E, LL, HR, LD)    // ┮  right heavy and left down light
        set(0x252F, HL, HR, LD)    // ┯  down light and horizontal heavy
        set(0x2530, LL, LR, HD)    // ┰  down heavy and horizontal light
        set(0x2531, HL, LR, HD)    // ┱  right light and left down heavy
        set(0x2532, LL, HR, HD)    // ┲  left light and right down heavy
        set(0x2533, HL, HR, HD)    // ┳  heavy down and horizontal

        set(0x2534, LL, LR, LU)    // ┴  light up and horizontal
        set(0x2535, HL, LR, LU)    // ┵  left heavy and right up light
        set(0x2536, LL, HR, LU)    // ┶  right heavy and left up light
        set(0x2537, HL, HR, LU)    // ┷  up light and horizontal heavy
        set(0x2538, LL, LR, HU)    // ┸  up heavy and horizontal light
        set(0x2539, HL, LR, HU)    // ┹  right light and left up heavy
        set(0x253A, LL, HR, HU)    // ┺  left light and right up heavy
        set(0x253B, HL, HR, HU)    // ┻  heavy up and horizontal

        // ── Crosses ─────────────────────────────────────────────────
        set(0x253C, LL, LR, LU, LD)  // ┼  light vertical and horizontal
        set(0x253D, HL, LR, LU, LD)  // ┽  left heavy and right vertical light
        set(0x253E, LL, HR, LU, LD)  // ┾  right heavy and left vertical light
        set(0x253F, HL, HR, LU, LD)  // ┿  vertical light and horizontal heavy
        set(0x2540, LL, LR, HU, LD)  // ╀  up heavy and down horizontal light
        set(0x2541, LL, LR, LU, HD)  // ╁  down heavy and up horizontal light
        set(0x2542, LL, LR, HU, HD)  // ╂  vertical heavy and horizontal light
        set(0x2543, HL, LR, HU, LD)  // ╃  left up heavy and right down light
        set(0x2544, LL, HR, HU, LD)  // ╄  right up heavy and left down light
        set(0x2545, HL, LR, LU, HD)  // ╅  left down heavy and right up light
        set(0x2546, LL, HR, LU, HD)  // ╆  right down heavy and left up light
        set(0x2547, HL, HR, HU, LD)  // ╇  down light and up horizontal heavy
        set(0x2548, HL, HR, LU, HD)  // ╈  up light and down horizontal heavy
        set(0x2549, HL, LR, HU, HD)  // ╉  right light and left vertical heavy
        set(0x254A, LL, HR, HU, HD)  // ╊  left light and right vertical heavy
        set(0x254B, HL, HR, HU, HD)  // ╋  heavy vertical and horizontal

        // ── Light/heavy half-lines (dashes) ─────────────────────────
        set(0x254C, LH)            // ╌  light double dash horizontal (approx)
        set(0x254D, HH)            // ╍  heavy double dash horizontal (approx)
        set(0x254E, LV)            // ╎  light double dash vertical (approx)
        set(0x254F, HV)            // ╏  heavy double dash vertical (approx)

        // ── Double lines ────────────────────────────────────────────
        setList(0x2550, DH)                     // ═  double horizontal
        setList(0x2551, DV)                     // ║  double vertical

        // Double / mixed corners
        setList(0x2552, doubleHSingleVDownRight())  // ╒  down single and right double
        setList(0x2553, singleHDoubleVDownRight())  // ╓  down double and right single
        setList(0x2554, doubleDownRight())          // ╔  double down and right

        setList(0x2555, doubleHSingleVDownLeft())   // ╕  down single and left double
        setList(0x2556, singleHDoubleVDownLeft())   // ╖  down double and left single
        setList(0x2557, doubleDownLeft())            // ╗  double down and left

        setList(0x2558, doubleHSingleVUpRight())    // ╘  up single and right double
        setList(0x2559, singleHDoubleVUpRight())    // ╙  up double and right single
        setList(0x255A, doubleUpRight())             // ╚  double up and right

        setList(0x255B, doubleHSingleVUpLeft())     // ╛  up single and left double
        setList(0x255C, singleHDoubleVUpLeft())     // ╜  up double and left single
        setList(0x255D, doubleUpLeft())              // ╝  double up and left

        // Double / mixed tees
        setList(0x255E, doubleHSingleRight())       // ╞  vertical single and right double
        setList(0x255F, singleVDoubleRight())       // ╟  vertical double and right single
        setList(0x2560, doubleVerticalRight())       // ╠  double vertical and right

        setList(0x2561, doubleHSingleLeft())        // ╡  vertical single and left double
        setList(0x2562, singleVDoubleLeft())        // ╢  vertical double and left single
        setList(0x2563, doubleVerticalLeft())         // ╣  double vertical and left

        setList(0x2564, singleHDoubleDown())        // ╤  down single and horizontal double
        setList(0x2565, doubleHSingleDown())        // ╥  down double and horizontal single
        setList(0x2566, doubleHorizontalDown())      // ╦  double down and horizontal

        setList(0x2567, singleHDoubleUp())          // ╧  up single and horizontal double
        setList(0x2568, doubleHSingleUp())          // ╨  up double and horizontal single
        setList(0x2569, doubleHorizontalUp())        // ╩  double up and horizontal

        // Double / mixed crosses
        setList(0x256A, doubleHSingleVCross())      // ╪  vertical single and horizontal double
        setList(0x256B, singleHDoubleVCross())      // ╫  vertical double and horizontal single
        setList(0x256C, doubleCross())               // ╬  double vertical and horizontal

        // ── Rounded corners ─────────────────────────────────────────
        setSpecial(0x256D, Special.ARC_DOWN_RIGHT)   // ╭
        setSpecial(0x256E, Special.ARC_DOWN_LEFT)    // ╮
        setSpecial(0x256F, Special.ARC_UP_LEFT)      // ╯
        setSpecial(0x2570, Special.ARC_UP_RIGHT)     // ╰

        // ── Diagonals ───────────────────────────────────────────────
        setSpecial(0x2571, Special.DIAGONAL_UPPER_RIGHT_TO_LOWER_LEFT)  // ╱
        setSpecial(0x2572, Special.DIAGONAL_UPPER_LEFT_TO_LOWER_RIGHT)  // ╲
        setSpecial(0x2573, Special.DIAGONAL_CROSS)                       // ╳

        // ── Half lines (U+2574-U+257F) ──────────────────────────────
        set(0x2574, LL)            // ╴  light left
        set(0x2575, LU)            // ╵  light up
        set(0x2576, LR)            // ╶  light right
        set(0x2577, LD)            // ╷  light down
        set(0x2578, HL)            // ╸  heavy left
        set(0x2579, HU)            // ╹  heavy up
        set(0x257A, HR)            // ╺  heavy right
        set(0x257B, HD)            // ╻  heavy down
        set(0x257C, LL, HR)        // ╼  light left and heavy right
        set(0x257D, LU, HD)        // ╽  light up and heavy down
        set(0x257E, HL, LR)        // ╾  heavy left and light right
        set(0x257F, HU, LD)        // ╿  heavy up and light down
    }

    /**
     * Renders a box drawing character at the given cell position.
     *
     * @param codepoint Unicode codepoint in the range U+2500-U+257F
     * @param x         pixel x of the cell's left edge
     * @param y         pixel y of the cell's top edge
     * @param cellWidth pixel width of one character cell
     * @param cellHeight pixel height of one character cell
     * @param color     foreground color
     */
    fun DrawScope.drawBoxDrawingChar(
        codepoint: Int,
        x: Float,
        y: Float,
        cellWidth: Float,
        cellHeight: Float,
        color: Color,
    ) {
        val index = codepoint - BOX_DRAWING_START
        if (index !in TABLE.indices) return
        val boxChar = TABLE[index] ?: return

        // Base stroke width derived from the thinner cell dimension, clamped to at least 1px.
        val baseStroke = (minOf(cellWidth, cellHeight) / 8f).coerceAtLeast(1f)

        // Handle special shapes first.
        boxChar.special?.let { special ->
            drawSpecial(special, x, y, cellWidth, cellHeight, color, baseStroke)
            return
        }

        // Draw line segments.
        for (seg in boxChar.segments) {
            val strokeWidth = baseStroke * seg.weight
            drawLine(
                color = color,
                start = Offset(x + seg.x1 * cellWidth, y + seg.y1 * cellHeight),
                end = Offset(x + seg.x2 * cellWidth, y + seg.y2 * cellHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Butt,
            )
        }
    }

    /**
     * Draws special shapes: arcs for rounded corners and diagonals.
     */
    private fun DrawScope.drawSpecial(
        special: Special,
        x: Float,
        y: Float,
        cellWidth: Float,
        cellHeight: Float,
        color: Color,
        baseStroke: Float,
    ) {
        val cx = x + cellWidth * 0.5f
        val cy = y + cellHeight * 0.5f
        val strokeWidth = baseStroke * LIGHT

        when (special) {
            Special.ARC_DOWN_RIGHT -> {
                // ╭ arc from center-bottom to center-right, curving through lower-right quadrant
                val path = Path().apply {
                    moveTo(cx, y + cellHeight)
                    cubicTo(cx, cy, cx, cy, x + cellWidth, cy)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Butt))
            }

            Special.ARC_DOWN_LEFT -> {
                // ╮ arc from center-left to center-bottom
                val path = Path().apply {
                    moveTo(x, cy)
                    cubicTo(cx, cy, cx, cy, cx, y + cellHeight)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Butt))
            }

            Special.ARC_UP_LEFT -> {
                // ╯ arc from center-top to center-left
                val path = Path().apply {
                    moveTo(cx, y)
                    cubicTo(cx, cy, cx, cy, x, cy)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Butt))
            }

            Special.ARC_UP_RIGHT -> {
                // ╰ arc from center-right to center-top
                val path = Path().apply {
                    moveTo(x + cellWidth, cy)
                    cubicTo(cx, cy, cx, cy, cx, y)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Butt))
            }

            Special.DIAGONAL_UPPER_RIGHT_TO_LOWER_LEFT -> {
                // ╱ top-right to bottom-left
                drawLine(
                    color = color,
                    start = Offset(x + cellWidth, y),
                    end = Offset(x, y + cellHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt,
                )
            }

            Special.DIAGONAL_UPPER_LEFT_TO_LOWER_RIGHT -> {
                // ╲ top-left to bottom-right
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(x + cellWidth, y + cellHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt,
                )
            }

            Special.DIAGONAL_CROSS -> {
                // ╳ both diagonals
                drawLine(
                    color = color,
                    start = Offset(x + cellWidth, y),
                    end = Offset(x, y + cellHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt,
                )
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(x + cellWidth, y + cellHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt,
                )
            }
        }
    }
}
