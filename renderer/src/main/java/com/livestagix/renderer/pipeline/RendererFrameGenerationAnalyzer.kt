package com.livestagix.renderer.pipeline

import kotlin.math.abs
import kotlin.math.max

/** CPU-readable luma plane extracted by a backend-specific video input stage. */
data class RendererLumaFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixels: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(rowStride >= width)
        require(pixels.size.toLong() >= rowStride.toLong() * height)
    }

    fun sample(x: Int, y: Int): Int =
        pixels[y * rowStride + x].toInt() and 0xFF
}

data class RendererMotionVector(
    val xPixels: Int,
    val yPixels: Int,
    val confidence: Float
)

data class RendererMotionGrid(
    val columns: Int,
    val rows: Int,
    val vectors: List<RendererMotionVector>
) {
    init {
        require(columns > 0 && rows > 0)
        require(vectors.size == columns * rows)
    }
}

/**
 * Shared block-matching motion estimator for frame-generation experiments.
 * It returns motion vectors only. Frame capture, GPU interpolation, cadence and
 * presentation remain backend-specific and are not implied by this CPU analysis.
 */
object RendererFrameGenerationAnalyzer {
    fun estimate(
        previous: RendererLumaFrame,
        current: RendererLumaFrame,
        columns: Int = 8,
        rows: Int = 8,
        searchRadius: Int = 4,
        patchRadius: Int = 3
    ): RendererMotionGrid {
        require(previous.width == current.width && previous.height == current.height) {
            "Frame dimensions must match"
        }
        require(columns in 1..previous.width && rows in 1..previous.height)
        require(searchRadius in 0..32)
        require(patchRadius in 1..16)

        val width = previous.width
        val height = previous.height
        val radius = patchRadius.coerceAtMost((width - 1) / 2).coerceAtMost((height - 1) / 2)
        val vectors = ArrayList<RendererMotionVector>(columns * rows)

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val centerX = ((column + 0.5f) * width / columns).toInt()
                    .coerceIn(radius, max(radius, width - radius - 1))
                val centerY = ((row + 0.5f) * height / rows).toInt()
                    .coerceIn(radius, max(radius, height - radius - 1))

                var bestSad = Long.MAX_VALUE
                var secondBestSad = Long.MAX_VALUE
                var bestX = 0
                var bestY = 0

                for (dy in -searchRadius..searchRadius) {
                    for (dx in -searchRadius..searchRadius) {
                        val candidateX = centerX + dx
                        val candidateY = centerY + dy
                        if (
                            candidateX - radius < 0 ||
                            candidateY - radius < 0 ||
                            candidateX + radius >= width ||
                            candidateY + radius >= height
                        ) continue

                        var sad = 0L
                        for (patchY in -radius..radius) {
                            for (patchX in -radius..radius) {
                                sad += abs(
                                    previous.sample(centerX + patchX, centerY + patchY) -
                                        current.sample(candidateX + patchX, candidateY + patchY)
                                )
                            }
                        }

                        if (sad < bestSad) {
                            secondBestSad = bestSad
                            bestSad = sad
                            bestX = dx
                            bestY = dy
                        } else if (sad < secondBestSad) {
                            secondBestSad = sad
                        }
                    }
                }

                val confidence = if (
                    bestSad == Long.MAX_VALUE ||
                    secondBestSad == Long.MAX_VALUE ||
                    secondBestSad <= 0L
                ) {
                    0f
                } else {
                    ((secondBestSad - bestSad).toFloat() / secondBestSad).coerceIn(0f, 1f)
                }
                vectors += RendererMotionVector(bestX, bestY, confidence)
            }
        }

        return RendererMotionGrid(columns, rows, vectors)
    }
}
