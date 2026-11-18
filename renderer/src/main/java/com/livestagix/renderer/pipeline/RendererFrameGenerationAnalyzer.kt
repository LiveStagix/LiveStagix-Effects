package com.livestagix.renderer.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val xPixels: Float,
    val yPixels: Float,
    val confidence: Float
) {
    init {
        require(xPixels.isFinite() && yPixels.isFinite())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class RendererMotionGrid(
    val columns: Int,
    val rows: Int,
    val vectors: List<RendererMotionVector>,
    val meanLumaDifference: Float = 0f,
    val acceptedRegions: Int = 0,
    val motionConfidence: Float = 0f,
    val sceneCutDetected: Boolean = false,
    /** RGBA8 field: red/green encode displacement, blue confidence, alpha is 255. */
    val fieldPixels: ByteArray = ByteArray(0)
) {
    init {
        require(columns > 0 && rows > 0)
        require(vectors.size == columns * rows)
        require(meanLumaDifference.isFinite() && meanLumaDifference >= 0f)
        require(acceptedRegions in 0..vectors.size)
        require(motionConfidence.isFinite() && motionConfidence in 0f..1f)
        require(fieldPixels.isEmpty() || fieldPixels.size == vectors.size * 4)
    }
}

/**
 * Port of LiveWP's regional FI motion estimator. It retains stationary-patch acceptance,
 * texture/confidence rejection, reverse-vector validation, prior-field smoothing and
 * scene-cut protection. GPU frame capture, interpolation, cadence and presentation remain
 * backend-specific.
 */
object RendererFrameGenerationAnalyzer {
    const val MAX_GRID_CELLS = 32
    const val SEARCH_RADIUS = 4
    const val PATCH_RADIUS = 2
    const val STATIONARY_SAD_THRESHOLD = 24
    const val MOTION_FIELD_MAX_PIXELS = 6f

    fun estimate(
        previous: RendererLumaFrame,
        current: RendererLumaFrame,
        columns: Int = 8,
        rows: Int = 4,
        searchRadius: Int = SEARCH_RADIUS,
        patchRadius: Int = PATCH_RADIUS,
        prior: RendererMotionGrid? = null
    ): RendererMotionGrid {
        require(previous.width == current.width && previous.height == current.height)
        require(columns in 1..previous.width && rows in 1..previous.height)
        require(columns * rows <= MAX_GRID_CELLS)
        require(searchRadius in 0..16 && patchRadius in 1..8)
        require(prior == null || prior.columns == columns && prior.rows == rows)

        val width = previous.width
        val height = previous.height
        val margin = 2 * searchRadius + patchRadius
        require(width > margin * 2 + 1 && height > margin * 2 + 1)

        var totalDifference = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                totalDifference += abs(previous.sample(x, y) - current.sample(x, y))
            }
        }
        val meanDifference = totalDifference / (width * height).toFloat()
        val vectors = MutableList(columns * rows) { RendererMotionVector(0f, 0f, 0f) }
        val fieldPixels = ByteArray(columns * rows * 4)
        var acceptedRegions = 0
        var strongestConfidence = 0f

        for (row in 0 until rows) {
            val cy = motionGridSampleCoordinate(row, rows, height, searchRadius, patchRadius)
            for (column in 0 until columns) {
                val cx = motionGridSampleCoordinate(column, columns, width, searchRadius, patchRadius)
                val index = row * columns + column
                val offset = index * 4
                val priorVector = prior?.vectors?.get(index)
                val priorConfidence = priorVector?.confidence ?: 0f
                val baseline = patchSad(previous, current, cx, cy, 0, 0, patchRadius)

                if (baseline < STATIONARY_SAD_THRESHOLD) {
                    val stable = (1f - baseline / (STATIONARY_SAD_THRESHOLD * 4f)).coerceIn(0f, 1f)
                    val vector = RendererMotionVector(0f, 0f, stable)
                    vectors[index] = vector
                    encodeVector(fieldPixels, offset, vector)
                    strongestConfidence = max(strongestConfidence, stable)
                    acceptedRegions++
                    continue
                }

                var best = baseline
                var second = baseline
                var bestDx = 0
                var bestDy = 0
                for (dy in -searchRadius..searchRadius) {
                    for (dx in -searchRadius..searchRadius) {
                        val sad = patchSad(previous, current, cx, cy, dx, dy, patchRadius)
                        if (sad < best) {
                            second = best
                            best = sad
                            bestDx = dx
                            bestDy = dy
                        } else if (sad < second) {
                            second = sad
                        }
                    }
                }

                val improvement = if (baseline > 0) (baseline - best) / baseline.toFloat() else 0f
                val ambiguity = (second - best) / max(1, second).toFloat()
                val texture = min(1f, patchGradient(previous, cx, cy, patchRadius) / 900f)
                var confidence =
                    min(1f, improvement * 5f) * min(1f, ambiguity * 6f) * texture
                var reliable =
                    baseline >= STATIONARY_SAD_THRESHOLD &&
                        (bestDx != 0 || bestDy != 0) &&
                        improvement >= 0.04f &&
                        texture >= 0.12f &&
                        confidence >= 0.18f

                if (reliable) {
                    val reverseX = cx + bestDx
                    val reverseY = cy + bestDy
                    val reverseBaseline = patchSad(
                        current, previous, reverseX, reverseY, 0, 0, patchRadius
                    )
                    var reverseBest = reverseBaseline
                    var reverseDx = 0
                    var reverseDy = 0
                    for (dy in -searchRadius..searchRadius) {
                        for (dx in -searchRadius..searchRadius) {
                            val sad = patchSad(
                                current, previous, reverseX, reverseY, dx, dy, patchRadius
                            )
                            if (sad < reverseBest) {
                                reverseBest = sad
                                reverseDx = dx
                                reverseDy = dy
                            }
                        }
                    }
                    val reverseImprovement = if (reverseBaseline > 0) {
                        (reverseBaseline - reverseBest) / reverseBaseline.toFloat()
                    } else {
                        0f
                    }
                    reliable = reverseImprovement >= 0.025f &&
                        abs(bestDx + reverseDx) <= 1 &&
                        abs(bestDy + reverseDy) <= 1
                }

                if (reliable) {
                    var dx = bestDx.toFloat()
                    var dy = bestDy.toFloat()
                    if (priorConfidence >= 0.20f && priorVector != null) {
                        val delta = sqrt(
                            (dx - priorVector.xPixels) * (dx - priorVector.xPixels) +
                                (dy - priorVector.yPixels) * (dy - priorVector.yPixels)
                        )
                        if (delta <= 2.5f) {
                            dx = priorVector.xPixels * 0.30f + dx * 0.70f
                            dy = priorVector.yPixels * 0.30f + dy * 0.70f
                            confidence = confidence * 0.75f + priorConfidence * 0.25f
                        }
                    }
                    val vector = RendererMotionVector(dx, dy, confidence.coerceIn(0f, 1f))
                    vectors[index] = vector
                    encodeVector(fieldPixels, offset, vector)
                    strongestConfidence = max(strongestConfidence, vector.confidence)
                    acceptedRegions++
                } else {
                    val vector = RendererMotionVector(0f, 0f, 0f)
                    vectors[index] = vector
                    encodeVector(fieldPixels, offset, vector)
                }
            }
        }

        val sceneCut = meanDifference > 52f && acceptedRegions < 4
        if (sceneCut) {
            for (index in vectors.indices) {
                vectors[index] = RendererMotionVector(0f, 0f, 0f)
                encodeVector(fieldPixels, index * 4, vectors[index])
            }
            acceptedRegions = 0
            strongestConfidence = 0f
        }

        return RendererMotionGrid(
            columns = columns,
            rows = rows,
            vectors = vectors,
            meanLumaDifference = meanDifference,
            acceptedRegions = acceptedRegions,
            motionConfidence = strongestConfidence,
            sceneCutDetected = sceneCut,
            fieldPixels = fieldPixels
        )
    }

    fun motionGridSampleCoordinate(
        index: Int,
        count: Int,
        sampleSize: Int,
        searchRadius: Int = SEARCH_RADIUS,
        patchRadius: Int = PATCH_RADIUS
    ): Int {
        require(count > 0 && index in 0 until count && sampleSize > 0)
        val margin = 2 * searchRadius + patchRadius
        require(sampleSize > margin * 2 + 1)
        val last = sampleSize - 1 - margin
        if (count <= 1) return sampleSize / 2
        return margin + ((last - margin) * (index / (count - 1f))).roundToInt()
    }

    private fun patchSad(
        previous: RendererLumaFrame,
        current: RendererLumaFrame,
        centerX: Int,
        centerY: Int,
        dx: Int,
        dy: Int,
        radius: Int
    ): Int {
        var sum = 0
        for (y in -radius..radius) {
            for (x in -radius..radius) {
                sum += abs(
                    previous.sample(centerX + x, centerY + y) -
                        current.sample(centerX + x + dx, centerY + y + dy)
                )
            }
        }
        return sum
    }

    private fun patchGradient(
        image: RendererLumaFrame,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Float {
        var sum = 0
        for (y in -radius..radius) {
            for (x in -radius..radius) {
                val value = image.sample(centerX + x, centerY + y)
                sum += abs(value - image.sample(centerX + x + 1, centerY + y))
                sum += abs(value - image.sample(centerX + x, centerY + y + 1))
            }
        }
        return sum.toFloat()
    }

    private fun encodeVector(output: ByteArray, offset: Int, vector: RendererMotionVector) {
        output[offset] = encodeDisplacement(vector.xPixels)
        output[offset + 1] = encodeDisplacement(vector.yPixels)
        output[offset + 2] = (vector.confidence * 255f).roundToInt()
            .coerceIn(0, 255).toByte()
        output[offset + 3] = 0xFF.toByte()
    }

    private fun encodeDisplacement(pixels: Float): Byte =
        ((pixels / MOTION_FIELD_MAX_PIXELS * 0.5f + 0.5f) * 255f)
            .roundToInt().coerceIn(0, 255).toByte()
}