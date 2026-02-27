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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Checks whether the given codepoint is a Unicode block element (U+2580-U+259F).
 */
internal fun isBlockElement(codepoint: Int): Boolean =
    codepoint in 0x2580..0x259F

/**
 * Renders a Unicode block element character (U+2580-U+259F) using Compose Canvas
 * rect/fill operations for pixel-perfect results.
 *
 * Block elements are geometric shapes that tile perfectly within a character cell.
 * Drawing them with rect fills avoids font rendering artifacts (anti-aliasing gaps,
 * misaligned baselines) that cause visible seams in TUI box-drawing and bar charts.
 */
internal fun DrawScope.drawBlockElement(
    codepoint: Int,
    x: Float,
    y: Float,
    cellWidth: Float,
    cellHeight: Float,
    fgColor: Color,
    bgColor: Color,
) {
    when (codepoint) {
        // ▀ Upper half block
        0x2580 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight / 2f),
            )
        }

        // ▁ Lower one eighth block
        0x2581 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight * 7f / 8f),
                size = Size(cellWidth, cellHeight / 8f),
            )
        }

        // ▂ Lower one quarter block
        0x2582 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight * 3f / 4f),
                size = Size(cellWidth, cellHeight / 4f),
            )
        }

        // ▃ Lower three eighths block
        0x2583 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight * 5f / 8f),
                size = Size(cellWidth, cellHeight * 3f / 8f),
            )
        }

        // ▄ Lower half block
        0x2584 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth, cellHeight / 2f),
            )
        }

        // ▅ Lower five eighths block
        0x2585 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight * 3f / 8f),
                size = Size(cellWidth, cellHeight * 5f / 8f),
            )
        }

        // ▆ Lower three quarters block
        0x2586 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 4f),
                size = Size(cellWidth, cellHeight * 3f / 4f),
            )
        }

        // ▇ Lower seven eighths block
        0x2587 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 8f),
                size = Size(cellWidth, cellHeight * 7f / 8f),
            )
        }

        // █ Full block
        0x2588 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
            )
        }

        // ▉ Left seven eighths block
        0x2589 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth * 7f / 8f, cellHeight),
            )
        }

        // ▊ Left three quarters block
        0x258A -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth * 3f / 4f, cellHeight),
            )
        }

        // ▋ Left five eighths block
        0x258B -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth * 5f / 8f, cellHeight),
            )
        }

        // ▌ Left half block
        0x258C -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight),
            )
        }

        // ▍ Left three eighths block
        0x258D -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth * 3f / 8f, cellHeight),
            )
        }

        // ▎ Left one quarter block
        0x258E -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 4f, cellHeight),
            )
        }

        // ▏ Left one eighth block
        0x258F -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 8f, cellHeight),
            )
        }

        // ▐ Right half block
        0x2590 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight),
            )
        }

        // ░ Light shade (25%)
        0x2591 -> {
            drawRect(
                color = fgColor.copy(alpha = 0.25f),
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
            )
        }

        // ▒ Medium shade (50%)
        0x2592 -> {
            drawRect(
                color = fgColor.copy(alpha = 0.5f),
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
            )
        }

        // ▓ Dark shade (75%)
        0x2593 -> {
            drawRect(
                color = fgColor.copy(alpha = 0.75f),
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
            )
        }

        // ▔ Upper one eighth block
        0x2594 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight / 8f),
            )
        }

        // ▕ Right one eighth block
        0x2595 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth * 7f / 8f, y),
                size = Size(cellWidth / 8f, cellHeight),
            )
        }

        // ▖ Quadrant lower left
        0x2596 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▗ Quadrant lower right
        0x2597 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▘ Quadrant upper left
        0x2598 -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▙ Quadrant upper left and lower left and lower right
        0x2599 -> {
            // Upper left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▚ Quadrant upper left and lower right
        0x259A -> {
            // Upper left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▛ Quadrant upper left and upper right and lower left
        0x259B -> {
            // Upper left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Upper right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▜ Quadrant upper left and upper right and lower right
        0x259C -> {
            // Upper left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Upper right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▝ Quadrant upper right
        0x259D -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▞ Quadrant upper right and lower left
        0x259E -> {
            // Upper right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }

        // ▟ Quadrant upper right and lower left and lower right
        0x259F -> {
            // Upper right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower left
            drawRect(
                color = fgColor,
                topLeft = Offset(x, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
            // Lower right
            drawRect(
                color = fgColor,
                topLeft = Offset(x + cellWidth / 2f, y + cellHeight / 2f),
                size = Size(cellWidth / 2f, cellHeight / 2f),
            )
        }
    }
}
