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
 * Represents a URL detected in terminal text that was not explicitly marked
 * with an OSC 8 hyperlink escape sequence.
 */
internal data class ImplicitLink(
    val url: String,
    val startCol: Int,
    val endCol: Int,
)

/**
 * Detects URLs in terminal line text that are not explicitly marked with
 * OSC 8 hyperlink sequences. Supports HTTP, HTTPS, FTP, SSH, and file URLs.
 *
 * Wide (fullwidth) characters are accounted for when mapping string positions
 * back to terminal column positions.
 */
internal class ImplicitLinkDetector {

    companion object {
        private val URL_PATTERN = Regex(
            """(?:https?|ftp|ssh|file)://[^\s<>"{}|\\^`\[\]]+""",
            RegexOption.IGNORE_CASE,
        )

        /** Characters that are stripped from the end of a URL when they lack a matching opener. */
        private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', ';', ':')

        /** Pairs of brackets whose trailing closer is only stripped when unmatched. */
        private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[')
    }

    /**
     * Detect all implicit links in [line].
     *
     * @return a list of [ImplicitLink] instances sorted by [ImplicitLink.startCol].
     */
    fun detectLinks(line: TerminalLine): List<ImplicitLink> {
        val cells = line.cells
        if (cells.isEmpty()) return emptyList()

        val (text, colMap) = buildTextAndColumnMap(cells)
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<ImplicitLink>()
        for (match in URL_PATTERN.findAll(text)) {
            val url = stripTrailingPunctuation(match.value)
            val startIndex = match.range.first
            // endIndex is the last character index of the (possibly trimmed) URL
            val endIndex = startIndex + url.length - 1

            val startCol = colMap[startIndex]
            val endCol = colMap[endIndex]
            results.add(ImplicitLink(url = url, startCol = startCol, endCol = endCol))
        }
        return results
    }

    /**
     * Detect the implicit link at a specific column, if any.
     * Useful for tap/click handling.
     *
     * @return the [ImplicitLink] whose column range contains [col], or `null`.
     */
    fun detectLinkAt(line: TerminalLine, col: Int): ImplicitLink? {
        return detectLinks(line).firstOrNull { col in it.startCol..it.endCol }
    }

    // ---- internal helpers ----

    /**
     * Converts the cell list into a plain-text string and builds a mapping
     * from each string character index to its terminal column position.
     *
     * Wide (width == 2) characters occupy two columns but produce only one
     * character in the string, so the column counter advances by the cell width.
     */
    private fun buildTextAndColumnMap(cells: List<TerminalLine.Cell>): Pair<String, IntArray> {
        val sb = StringBuilder(cells.size)
        val colList = mutableListOf<Int>()

        var col = 0
        for (cell in cells) {
            val ch = if (cell.char == '\u0000') ' ' else cell.char
            sb.append(ch)
            colList.add(col)
            col += cell.width
        }

        return sb.toString() to colList.toIntArray()
    }

    /**
     * Strip trailing punctuation characters that are unlikely to be part of the URL.
     *
     * Brackets (`)`, `]`) are only stripped when they do not have a matching opener
     * earlier in the URL (e.g. Wikipedia URLs with parentheses should be preserved).
     */
    private fun stripTrailingPunctuation(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            if (last !in TRAILING_PUNCTUATION) break

            val opener = BRACKET_PAIRS[last]
            if (opener != null) {
                val openCount = url.count { it == opener }
                val closeCount = url.count { it == last }
                if (closeCount <= openCount) break
            }

            url = url.dropLast(1)
        }
        return url
    }
}
