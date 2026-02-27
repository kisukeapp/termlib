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

/**
 * Options controlling how terminal search is performed.
 */
internal data class SearchOptions(
    val caseSensitive: Boolean = false,
    val regex: Boolean = false,
    val wholeWord: Boolean = false,
    val wrapAround: Boolean = true
)

/**
 * Search engine for finding text within terminal lines and scrollback buffer.
 *
 * Handles wide characters (CJK), combining characters, and continuation lines
 * that need to be joined for cross-line matching.
 */
internal class SearchEngine {

    /**
     * Search for [query] across the visible [lines] and [scrollback] buffer.
     *
     * Scrollback lines come first (negative row indices as stored), followed by
     * visible screen lines. Continuation lines are joined so that a match can
     * span across a soft-wrapped boundary.
     *
     * @return a list of [SearchHighlight] for every match found, with none
     *         marked as current (callers should set isCurrent as needed).
     */
    fun search(
        query: String,
        options: SearchOptions,
        lines: List<TerminalLine>,
        scrollback: List<TerminalLine>
    ): List<SearchHighlight> {
        if (query.isEmpty()) return emptyList()

        val pattern = buildPattern(query, options)
        val allLines = scrollback + lines

        val results = mutableListOf<SearchHighlight>()
        var i = 0
        while (i < allLines.size) {
            // Collect a run of continuation lines starting at i.
            val runStart = i
            i++
            while (i < allLines.size && allLines[i].continuation) {
                i++
            }
            val run = allLines.subList(runStart, i)

            // Build a combined searchable string and column map for the run.
            val (text, colMap, rowMap) = buildRunSearchable(run)

            val matchSequence = pattern.findAll(text)
            for (match in matchSequence) {
                val highlights = matchToHighlights(match.range, colMap, rowMap, run)
                results.addAll(highlights)
            }
        }
        return results
    }

    /**
     * Return the index of the next match after [currentIndex].
     *
     * @param currentIndex the currently highlighted match index, or -1 if none.
     * @param total the total number of matches.
     * @param wrapAround whether to wrap from the last match to the first.
     * @return the new index, or -1 if there are no matches or wrapping is
     *         disabled and the end has been reached.
     */
    fun findNext(currentIndex: Int, total: Int, wrapAround: Boolean): Int {
        if (total <= 0) return -1
        val next = currentIndex + 1
        return when {
            next < total -> next
            wrapAround -> 0
            else -> -1
        }
    }

    /**
     * Return the index of the previous match before [currentIndex].
     *
     * @param currentIndex the currently highlighted match index, or -1 if none.
     * @param total the total number of matches.
     * @param wrapAround whether to wrap from the first match to the last.
     * @return the new index, or -1 if there are no matches or wrapping is
     *         disabled and the beginning has been reached.
     */
    fun findPrevious(currentIndex: Int, total: Int, wrapAround: Boolean): Int {
        if (total <= 0) return -1
        val prev = currentIndex - 1
        return when {
            prev >= 0 -> prev
            wrapAround -> total - 1
            else -> -1
        }
    }

    // ---- Private helpers ----

    /**
     * Convert a list of cells into a searchable plain-text string together
     * with a mapping from each string index back to the column position in the
     * terminal line.
     *
     * Wide (fullwidth / CJK) characters occupy 2 columns but produce a single
     * character in the string. Combining characters are appended to the string
     * but share the column of their base character.
     *
     * @return a [Pair] of the text and an [IntArray] where element `j` is the
     *         column index that string index `j` maps to.
     */
    private fun cellsToSearchable(cells: List<TerminalLine.Cell>): Pair<String, IntArray> {
        val sb = StringBuilder()
        val cols = mutableListOf<Int>()
        var col = 0
        for (cell in cells) {
            val ch = if (cell.char == '\u0000') ' ' else cell.char
            sb.append(ch)
            cols.add(col)
            for (combining in cell.combiningChars) {
                sb.append(combining)
                cols.add(col)
            }
            col += cell.width
        }
        // Trim trailing spaces (and their column entries) so that matches
        // don't span across padding, but keep at least an empty string.
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') {
            end--
        }
        return Pair(sb.substring(0, end), cols.toIntArray().copyOf(end))
    }

    /**
     * Build the compiled [Regex] from the user's query and options.
     */
    private fun buildPattern(query: String, options: SearchOptions): Regex {
        var patternText = if (options.regex) {
            query
        } else {
            Regex.escape(query)
        }
        if (options.wholeWord) {
            patternText = "\\b$patternText\\b"
        }
        val regexOptions = mutableSetOf<RegexOption>()
        if (!options.caseSensitive) {
            regexOptions.add(RegexOption.IGNORE_CASE)
        }
        return Regex(patternText, regexOptions)
    }

    /**
     * Intermediate representation combining the searchable text of a run of
     * continuation lines.
     *
     * @property text the concatenated text of all lines in the run.
     * @property colMap string-index to column mapping (size == text.length).
     * @property rowMap string-index to index-within-run mapping.
     */
    private data class RunSearchable(
        val text: String,
        val colMap: IntArray,
        val rowMap: IntArray
    )

    /**
     * Build a single searchable string for a run of continuation lines.
     */
    private fun buildRunSearchable(run: List<TerminalLine>): RunSearchable {
        val textBuilder = StringBuilder()
        val colList = mutableListOf<Int>()
        val rowList = mutableListOf<Int>()

        for ((runIndex, line) in run.withIndex()) {
            val (lineText, lineCols) = cellsToSearchable(line.cells)
            textBuilder.append(lineText)
            for (c in lineCols) {
                colList.add(c)
                rowList.add(runIndex)
            }
        }

        return RunSearchable(
            text = textBuilder.toString(),
            colMap = colList.toIntArray(),
            rowMap = rowList.toIntArray()
        )
    }

    /**
     * Convert a match range within the combined run string into one or more
     * [SearchHighlight] entries (one per terminal row the match spans).
     */
    private fun matchToHighlights(
        range: IntRange,
        colMap: IntArray,
        rowMap: IntArray,
        run: List<TerminalLine>
    ): List<SearchHighlight> {
        if (range.isEmpty() || colMap.isEmpty()) return emptyList()

        val highlights = mutableListOf<SearchHighlight>()

        val startIdx = range.first
        val endIdx = range.last // inclusive string index

        val firstRunRow = rowMap[startIdx]
        val lastRunRow = rowMap[endIdx]

        for (runRow in firstRunRow..lastRunRow) {
            val terminalRow = run[runRow].row

            val segStartCol = if (runRow == firstRunRow) {
                colMap[startIdx]
            } else {
                0
            }

            val segEndCol = if (runRow == lastRunRow) {
                // endCol is the column *after* the last matched character.
                val lastCharCol = colMap[endIdx]
                // Account for the width of the character at the last position.
                val lastCharWidth = findCellWidth(run[runRow].cells, lastCharCol)
                lastCharCol + lastCharWidth
            } else {
                // Match continues onto the next line; highlight to end of this line.
                run[runRow].cells.sumOf { it.width }
            }

            highlights.add(SearchHighlight(row = terminalRow, startCol = segStartCol, endCol = segEndCol))
        }
        return highlights
    }

    /**
     * Find the width of the cell at a given column position.
     */
    private fun findCellWidth(cells: List<TerminalLine.Cell>, col: Int): Int {
        var currentCol = 0
        for (cell in cells) {
            if (currentCol == col) return cell.width
            currentCol += cell.width
            if (currentCol > col) break
        }
        return 1
    }
}
