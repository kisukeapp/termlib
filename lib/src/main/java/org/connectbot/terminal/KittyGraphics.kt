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
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.io.encoding.Base64

/**
 * Implements the Kitty graphics protocol for inline image display.
 *
 * The Kitty graphics protocol uses APC sequences with a `_G` prefix:
 * ```
 * APC G <key>=<value>,<key>=<value>,...; <payload> ST
 * ```
 *
 * Supported actions:
 * - `a=t` / `a=T`: Transmit image data (direct, file, or temp file)
 * - `a=p`: Place (display) a previously transmitted image
 * - `a=d`: Delete images by ID or position
 * - `a=q`: Query protocol support
 *
 * Image data may arrive in multiple chunks (indicated by `m=1`) and is
 * accumulated until the final chunk (`m=0`) before decoding.
 */
internal class KittyGraphics(private val imageManager: ImageManager) {

    /**
     * Response to be sent back to the application over the PTY.
     *
     * @param message The full response payload (e.g., `_Gi=1;OK`).
     */
    data class Response(val message: String)

    /**
     * Buffer for accumulating multi-chunk transmission data, keyed by image ID.
     */
    private val transmissionBuffer = mutableMapOf<Int, ByteArray>()

    /**
     * Metadata stored from the first chunk of a multi-chunk transmission so that
     * format, dimensions, and display parameters are available when the final
     * chunk arrives.
     */
    private val transmissionParams = mutableMapOf<Int, Map<String, String>>()

    /**
     * Handle a Kitty graphics APC sequence.
     *
     * @param data The content between `_G` and `ST`, e.g. `a=t,f=100,i=1;base64data`.
     * @param cursorRow The current cursor row (used for image placement).
     * @param cursorCol The current cursor column (used for image placement).
     * @return A [Response] to send back to the application, or `null` if no
     *     response is required (e.g., when `q=1` suppresses OK responses).
     */
    fun handleSequence(data: String, cursorRow: Int, cursorCol: Int): Response? {
        val semicolonIndex = data.indexOf(';')
        val paramString: String
        val payload: String

        if (semicolonIndex >= 0) {
            paramString = data.substring(0, semicolonIndex)
            payload = data.substring(semicolonIndex + 1)
        } else {
            paramString = data
            payload = ""
        }

        val params = parseParams(paramString)
        val action = params["a"] ?: "t"
        val quiet = params["q"]?.toIntOrNull() ?: 0

        return when (action) {
            "t", "T" -> handleTransmit(params, payload, cursorRow, cursorCol, quiet)
            "p" -> handlePlace(params, cursorRow, cursorCol, quiet)
            "d" -> handleDelete(params, quiet)
            "q" -> handleQuery(params, quiet)
            else -> errorResponse(params, "ENOTSUPPORTED:unsupported action $action", quiet)
        }
    }

    /**
     * Parse comma-separated key=value pairs into a map.
     */
    private fun parseParams(paramString: String): Map<String, String> {
        if (paramString.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in paramString.split(',')) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex > 0) {
                val key = pair.substring(0, eqIndex)
                val value = pair.substring(eqIndex + 1)
                result[key] = value
            }
        }
        return result
    }

    /**
     * Handle image data transmission (`a=t` or `a=T`).
     *
     * Data may arrive in multiple chunks. When `m=1`, the chunk is buffered.
     * When `m=0` (or absent), all buffered data is concatenated, base64-decoded,
     * and the resulting image is stored in the [ImageManager].
     *
     * For `a=T`, the image is also immediately placed at the cursor position.
     */
    private fun handleTransmit(
        params: Map<String, String>,
        payload: String,
        cursorRow: Int,
        cursorCol: Int,
        quiet: Int
    ): Response? {
        val imageId = params["i"]?.toIntOrNull() ?: 0
        val more = params["m"]?.toIntOrNull() ?: 0
        val transmissionType = params["t"] ?: "d"

        // Only direct transmission is supported; file-based transmissions are
        // rejected for security.
        if (transmissionType != "d") {
            return errorResponse(params, "ENOTSUPPORTED:only direct transmission is supported", quiet)
        }

        if (more == 1) {
            // Accumulate chunk
            val existingData = transmissionBuffer[imageId]
            val chunkBytes = try {
                Base64.Default.decode(payload)
            } catch (e: IllegalArgumentException) {
                return errorResponse(params, "EBADDATA:invalid base64 payload", quiet)
            }

            transmissionBuffer[imageId] = if (existingData != null) {
                existingData + chunkBytes
            } else {
                chunkBytes
            }

            // Store params from the first chunk
            if (!transmissionParams.containsKey(imageId)) {
                transmissionParams[imageId] = params
            }

            // No response for intermediate chunks
            return null
        }

        // Final chunk (m=0 or absent) -- decode and store the image.
        val finalChunkBytes = try {
            if (payload.isNotEmpty()) Base64.Default.decode(payload) else ByteArray(0)
        } catch (e: IllegalArgumentException) {
            transmissionBuffer.remove(imageId)
            transmissionParams.remove(imageId)
            return errorResponse(params, "EBADDATA:invalid base64 payload", quiet)
        }

        val allBytes = transmissionBuffer.remove(imageId)?.let { buffered ->
            buffered + finalChunkBytes
        } ?: finalChunkBytes

        // Merge stored params from the first chunk with the current params.
        val mergedParams = (transmissionParams.remove(imageId) ?: emptyMap()) + params

        if (allBytes.isEmpty()) {
            return errorResponse(mergedParams, "ENODATA:no image data received", quiet)
        }

        val format = mergedParams["f"]?.toIntOrNull() ?: 32
        val bitmap = decodeBitmap(allBytes, format, mergedParams)
            ?: return errorResponse(mergedParams, "EBADDATA:failed to decode image data", quiet)

        val action = mergedParams["a"] ?: "t"
        val cols = mergedParams["c"]?.toIntOrNull() ?: calculateCellSpan(bitmap.width)
        val rows = mergedParams["r"]?.toIntOrNull() ?: calculateCellSpan(bitmap.height)
        val zIndex = mergedParams["z"]?.toIntOrNull() ?: 0

        val placementId = if (action == "T") {
            // Transmit and display: place immediately at cursor position.
            imageManager.placeImage(
                bitmap = bitmap,
                row = cursorRow,
                col = cursorCol,
                widthCells = cols,
                heightCells = rows,
                zIndex = zIndex
            )
        } else {
            // Transmit only: store with a temporary off-screen placement so
            // the bitmap is retained for a later `a=p` command.
            imageManager.placeImage(
                bitmap = bitmap,
                row = -1,
                col = -1,
                widthCells = cols,
                heightCells = rows,
                zIndex = zIndex
            )
        }

        return okResponse(imageId.takeIf { it != 0 } ?: placementId, quiet)
    }

    /**
     * Handle image placement (`a=p`).
     *
     * Looks up a previously transmitted image by ID and creates a visible
     * placement at the current cursor position.
     */
    private fun handlePlace(
        params: Map<String, String>,
        cursorRow: Int,
        cursorCol: Int,
        quiet: Int
    ): Response? {
        val imageId = params["i"]?.toIntOrNull()
            ?: return errorResponse(params, "EINVAL:image id required for placement", quiet)

        val placements = imageManager.getPlacements()
        val existing = placements.find { it.id == imageId }
            ?: return errorResponse(params, "ENOENT:image $imageId not found", quiet)

        val cols = params["c"]?.toIntOrNull() ?: existing.widthCells
        val rows = params["r"]?.toIntOrNull() ?: existing.heightCells
        val zIndex = params["z"]?.toIntOrNull() ?: existing.zIndex

        val placementId = imageManager.placeImage(
            bitmap = existing.bitmap,
            row = cursorRow,
            col = cursorCol,
            widthCells = cols,
            heightCells = rows,
            zIndex = zIndex
        )

        return okResponse(placementId, quiet)
    }

    /**
     * Handle image deletion (`a=d`).
     *
     * If an image ID is specified (`i=<id>`), that specific image is removed.
     * Otherwise all images are cleared.
     */
    private fun handleDelete(
        params: Map<String, String>,
        quiet: Int
    ): Response? {
        val imageId = params["i"]?.toIntOrNull()

        if (imageId != null) {
            imageManager.removeImage(imageId)
        } else {
            imageManager.clear()
        }

        return okResponse(imageId ?: 0, quiet)
    }

    /**
     * Handle protocol support query (`a=q`).
     *
     * Returns an OK response so the application knows that the Kitty graphics
     * protocol is supported.
     */
    private fun handleQuery(
        params: Map<String, String>,
        quiet: Int
    ): Response? {
        val imageId = params["i"]?.toIntOrNull() ?: 0
        return okResponse(imageId, quiet)
    }

    /**
     * Decode raw bytes into a [Bitmap] according to the specified format.
     *
     * @param data The raw image bytes (after base64 decoding).
     * @param format The pixel format: 24 = RGB, 32 = RGBA, 100 = PNG.
     * @param params The full parameter map (used to read `s` and `v` for raw formats).
     * @return A decoded [Bitmap], or `null` if decoding fails.
     */
    private fun decodeBitmap(
        data: ByteArray,
        format: Int,
        params: Map<String, String>
    ): Bitmap? {
        return when (format) {
            100 -> {
                // PNG encoded data
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }
            24 -> {
                // Raw RGB pixels
                val width = params["s"]?.toIntOrNull() ?: return null
                val height = params["v"]?.toIntOrNull() ?: return null
                if (width <= 0 || height <= 0) return null
                val expectedSize = width * height * 3
                if (data.size < expectedSize) return null

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val offset = i * 3
                    val r = data[offset].toInt() and 0xFF
                    val g = data[offset + 1].toInt() and 0xFF
                    val b = data[offset + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                bitmap
            }
            32 -> {
                // Raw RGBA pixels
                val width = params["s"]?.toIntOrNull() ?: return null
                val height = params["v"]?.toIntOrNull() ?: return null
                if (width <= 0 || height <= 0) return null
                val expectedSize = width * height * 4
                if (data.size < expectedSize) return null

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val offset = i * 4
                    val r = data[offset].toInt() and 0xFF
                    val g = data[offset + 1].toInt() and 0xFF
                    val b = data[offset + 2].toInt() and 0xFF
                    val a = data[offset + 3].toInt() and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                bitmap
            }
            else -> null
        }
    }

    /**
     * Estimate the number of terminal cells needed to span the given pixel dimension.
     *
     * Uses a rough heuristic of 8 pixels per cell. A real implementation would
     * use the actual cell size reported by the terminal.
     */
    private fun calculateCellSpan(pixels: Int): Int {
        val cellSize = 8
        return (pixels + cellSize - 1) / cellSize
    }

    /**
     * Build a success response.
     *
     * @param imageId The image ID to include in the response.
     * @param quiet The quiet level: 1 suppresses OK responses, 2 suppresses all.
     * @return A [Response] with the OK message, or `null` if suppressed.
     */
    private fun okResponse(imageId: Int, quiet: Int): Response? {
        if (quiet >= 1) return null
        return Response("_Gi=$imageId;OK")
    }

    /**
     * Build an error response.
     *
     * @param params The parameter map (used to extract image ID for the response).
     * @param errorMessage The error description to include.
     * @param quiet The quiet level: 2 suppresses error responses.
     * @return A [Response] with the error message, or `null` if suppressed.
     */
    private fun errorResponse(params: Map<String, String>, errorMessage: String, quiet: Int): Response? {
        if (quiet >= 2) return null
        val imageId = params["i"]?.toIntOrNull() ?: 0
        return Response("_Gi=$imageId;$errorMessage")
    }
}
