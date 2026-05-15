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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalZoomComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fontSizeState: MutableState<Float>

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

    private fun TerminalEmulatorImpl.getSnapshot(): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        processPendingUpdates()
        return snapshot.value
    }

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

    @Test
    fun testExtremeZoomViaComposePreservesLogicalLinesEachStep() = runBlocking {
        val emulator = createEmulator(rows = 24, cols = 80)

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

        composeRule.setContent {
            // Start very small so the initial width is wide enough that `ll` doesn't wrap.
            // This matches the real scenario: wide terminal, then zoom in/out.
            fontSizeState = remember { mutableStateOf(4f) }
            val density = LocalDensity.current
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(900.dp, 500.dp),
                initialFontSize = fontSizeState.value.sp,
                minFontSize = 3.sp,
                maxFontSize = 48.sp,
                backgroundColor = Color.Black,
                foregroundColor = Color.White,
                keyboardEnabled = false,
                showSoftKeyboard = false
            )
            // Keep state in composition so tests can update it.
            @Suppress("UNUSED_VARIABLE")
            val _density = density
        }

        composeRule.waitForIdle()

        repeat(3) {
            for (line in llTabLines) {
                emulator.writeAndSync("$line\r\n")
            }
        }

        // Baseline check before any resize: logical lines should already match.
        run {
            val baseline = emulator.getSnapshot()
            val logical = extractLogicalLines(baseline.scrollback + baseline.lines)
            val counts = logical.groupingBy { it }.eachCount()
            for (expected in expectedLines) {
                val count = counts[expected] ?: 0
                assertEquals(
                    "Baseline mismatch before resize for '$expected' (rows=${emulator.dimensions.rows}, " +
                        "cols=${emulator.dimensions.columns})",
                    3,
                    count
                )
            }
        }

        val zoomSizes = listOf(24f, 8f, 32f, 6f, 20f, 4f)
        for (size in zoomSizes) {
            composeRule.runOnUiThread {
                fontSizeState.value = size
            }

            composeRule.waitForIdle()

            val snapshot = emulator.getSnapshot()
            val dims = emulator.dimensions
            val logical = extractLogicalLines(snapshot.scrollback + snapshot.lines)
            val logicalCounts = logical.groupingBy { it }.eachCount()

            for (expected in expectedLines) {
                val count = logicalCounts[expected] ?: 0
                if (count != 3) {
                    val related = logical.filter { it.contains("drwxr-xr-x@") || it.contains("total 204816") }
                    throw AssertionError(
                        "Missing or duplicated logical line after zoom size $size (rows=${dims.rows}, " +
                            "cols=${dims.columns}): '$expected'. Count=$count. Related=$related"
                    )
                }
            }
            assertTrue(
                "Unexpected fragments after zoom size $size: $logical",
                logical.all { it in expectedLines }
            )
        }
    }
}
