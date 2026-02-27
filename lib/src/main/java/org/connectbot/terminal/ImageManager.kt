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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe manager for inline image placements in the terminal.
 *
 * @param maxMemoryBytes Maximum memory budget for stored bitmaps. When exceeded,
 *     the oldest images (by ID order) are evicted until usage is within budget.
 */
internal class ImageManager(private val maxMemoryBytes: Long = 320L * 1024L * 1024L) {

    private val placements = ConcurrentHashMap<Int, ImagePlacement>()
    private val nextId = AtomicInteger(1)
    private val currentMemory = AtomicLong(0L)

    /**
     * Store an image at the given cell position.
     *
     * @return The unique ID assigned to this placement.
     */
    fun placeImage(
        bitmap: Bitmap,
        row: Int,
        col: Int,
        widthCells: Int,
        heightCells: Int,
        zIndex: Int = 0
    ): Int {
        val id = nextId.getAndIncrement()
        val placement = ImagePlacement(
            id = id,
            bitmap = bitmap,
            row = row,
            col = col,
            widthCells = widthCells,
            heightCells = heightCells,
            zIndex = zIndex
        )

        placements[id] = placement
        currentMemory.addAndGet(bitmap.byteCount.toLong())

        evictIfNeeded()

        return id
    }

    /**
     * Remove an image by its unique ID.
     */
    fun removeImage(id: Int) {
        val removed = placements.remove(id)
        if (removed != null) {
            currentMemory.addAndGet(-removed.bitmap.byteCount.toLong())
        }
    }

    /**
     * Remove images that have scrolled entirely above the visible area.
     *
     * An image is considered scrolled off when its bottom edge
     * (`row + heightCells`) is above [topVisibleRow] minus [scrollbackSize].
     */
    fun cleanupScrolledImages(topVisibleRow: Int, scrollbackSize: Int) {
        val cutoff = topVisibleRow - scrollbackSize
        val iterator = placements.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val p = entry.value
            if (p.row + p.heightCells < cutoff) {
                currentMemory.addAndGet(-p.bitmap.byteCount.toLong())
                iterator.remove()
            }
        }
    }

    /**
     * Return a snapshot of all current placements for rendering.
     *
     * The returned list is sorted by [ImagePlacement.zIndex] ascending so that
     * higher z-index images are drawn on top.
     */
    fun getPlacements(): List<ImagePlacement> {
        return placements.values.sortedBy { it.zIndex }
    }

    /**
     * Remove all stored images and reset memory tracking.
     */
    fun clear() {
        placements.clear()
        currentMemory.set(0L)
    }

    /**
     * Return the current estimated memory usage in bytes across all stored bitmaps.
     */
    fun memoryUsage(): Long {
        return currentMemory.get()
    }

    /**
     * Evict the oldest images (lowest IDs first) until memory usage is within budget.
     */
    private fun evictIfNeeded() {
        while (currentMemory.get() > maxMemoryBytes) {
            // Find the placement with the smallest ID (oldest).
            val oldest = placements.keys.minOrNull() ?: break
            removeImage(oldest)
        }
    }
}
