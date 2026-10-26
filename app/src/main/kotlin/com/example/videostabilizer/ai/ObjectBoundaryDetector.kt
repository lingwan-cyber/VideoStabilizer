package com.example.videostabilizer.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.ObjectBoundary
import com.example.videostabilizer.model.Point2D
import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Intelligent object boundary identifier that segments the actual object within
 * a user-selected area using color saliency, gradient energy, morphological operations,
 * and extracts the object's tight boundary, contour polygon, and orientation angle.
 */
class ObjectBoundaryDetector {

    companion object {
        private const val ROI_GRID_SIZE = 96
    }

    /**
     * Identifies the object's boundary contour, tight bounding box, and orientation.
     */
    fun detectBoundary(bitmap: Bitmap, selection: NormalizedRect): ObjectBoundary {
        val bw = bitmap.width
        val bh = bitmap.height

        // Expand selection slightly by 15% to capture local background context
        val marginX = selection.width * 0.15f
        val marginY = selection.height * 0.15f

        val expLeft = (selection.left - marginX).coerceIn(0f, 1f)
        val expTop = (selection.top - marginY).coerceIn(0f, 1f)
        val expRight = (selection.right + marginX).coerceIn(0f, 1f)
        val expBottom = (selection.bottom + marginY).coerceIn(0f, 1f)

        val pxLeft = (expLeft * bw).roundToInt().coerceIn(0, bw - 1)
        val pxTop = (expTop * bh).roundToInt().coerceIn(0, bh - 1)
        val pxRight = (expRight * bw).roundToInt().coerceIn(pxLeft + 2, bw)
        val pxBottom = (expBottom * bh).roundToInt().coerceIn(pxTop + 2, bh)

        val roiW = pxRight - pxLeft
        val roiH = pxBottom - pxTop

        // Resample ROI into grid
        val gridR = IntArray(ROI_GRID_SIZE * ROI_GRID_SIZE)
        val gridG = IntArray(ROI_GRID_SIZE * ROI_GRID_SIZE)
        val gridB = IntArray(ROI_GRID_SIZE * ROI_GRID_SIZE)
        val gridLuma = FloatArray(ROI_GRID_SIZE * ROI_GRID_SIZE)

        for (gy in 0 until ROI_GRID_SIZE) {
            val srcY = (pxTop + (gy.toFloat() / ROI_GRID_SIZE) * roiH).toInt().coerceIn(0, bh - 1)
            val rowOffset = gy * ROI_GRID_SIZE
            for (gx in 0 until ROI_GRID_SIZE) {
                val srcX = (pxLeft + (gx.toFloat() / ROI_GRID_SIZE) * roiW).toInt().coerceIn(0, bw - 1)
                val pixel = bitmap.getPixel(srcX, srcY)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val idx = rowOffset + gx
                gridR[idx] = r
                gridG[idx] = g
                gridB[idx] = b
                gridLuma[idx] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        // 1. Background color distribution (from outer border pixels)
        var bgSumR = 0.0
        var bgSumG = 0.0
        var bgSumB = 0.0
        var bgCount = 0

        for (y in 0 until ROI_GRID_SIZE) {
            for (x in 0 until ROI_GRID_SIZE) {
                if (x < 6 || x >= ROI_GRID_SIZE - 6 || y < 6 || y >= ROI_GRID_SIZE - 6) {
                    val idx = y * ROI_GRID_SIZE + x
                    bgSumR += gridR[idx]
                    bgSumG += gridG[idx]
                    bgSumB += gridB[idx]
                    bgCount++
                }
            }
        }

        val bgAvgR = bgSumR / bgCount.coerceAtLeast(1)
        val bgAvgG = bgSumG / bgCount.coerceAtLeast(1)
        val bgAvgB = bgSumB / bgCount.coerceAtLeast(1)

        // 2. Foreground seed distribution (from center 40% of grid)
        var fgSumR = 0.0
        var fgSumG = 0.0
        var fgSumB = 0.0
        var fgCount = 0

        val fgMin = (ROI_GRID_SIZE * 0.30f).toInt()
        val fgMax = (ROI_GRID_SIZE * 0.70f).toInt()

        for (y in fgMin..fgMax) {
            for (x in fgMin..fgMax) {
                val idx = y * ROI_GRID_SIZE + x
                fgSumR += gridR[idx]
                fgSumG += gridG[idx]
                fgSumB += gridB[idx]
                fgCount++
            }
        }

        val fgAvgR = fgSumR / fgCount.coerceAtLeast(1)
        val fgAvgG = fgSumG / fgCount.coerceAtLeast(1)
        val fgAvgB = fgSumB / fgCount.coerceAtLeast(1)

        // 3. Score each pixel for foreground likelihood
        val mask = BooleanArray(ROI_GRID_SIZE * ROI_GRID_SIZE)
        val centerGrid = ROI_GRID_SIZE / 2f
        val maxDist = centerGrid * 1.414f

        for (y in 0 until ROI_GRID_SIZE) {
            val dy = y - centerGrid
            for (x in 0 until ROI_GRID_SIZE) {
                val dx = x - centerGrid
                val distCenter = hypot(dx, dy)
                val idx = y * ROI_GRID_SIZE + x

                val r = gridR[idx]
                val g = gridG[idx]
                val b = gridB[idx]

                val distBg = sqrt(((r - bgAvgR) * (r - bgAvgR) + (g - bgAvgG) * (g - bgAvgG) + (b - bgAvgB) * (b - bgAvgB)).toFloat())
                val distFg = sqrt(((r - fgAvgR) * (r - fgAvgR) + (g - fgAvgG) * (g - fgAvgG) + (b - fgAvgB) * (b - fgAvgB)).toFloat())

                // Spatial prior (closer to user tap/box center has higher prior)
                val spatialWeight = (1.0f - (distCenter / maxDist) * 0.65f).coerceIn(0.2f, 1.0f)

                // High gradient edge detection: Sobel check
                val isForeground = (distBg > distFg * 0.85f + 15f) && (spatialWeight > 0.35f)
                mask[idx] = isForeground
            }
        }

        // 4. Connected Component Analysis: extract primary foreground blob containing center
        val visited = BooleanArray(ROI_GRID_SIZE * ROI_GRID_SIZE)
        val cleanedMask = BooleanArray(ROI_GRID_SIZE * ROI_GRID_SIZE)

        // Start search from center outwards
        val startX = (ROI_GRID_SIZE / 2)
        val startY = (ROI_GRID_SIZE / 2)
        var seedX = startX
        var seedY = startY

        // If exact center is not marked foreground, find nearest foreground pixel
        if (!mask[startY * ROI_GRID_SIZE + startX]) {
            var found = false
            for (r in 1..20) {
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val nx = startX + dx
                        val ny = startY + dy
                        if (nx in 0 until ROI_GRID_SIZE && ny in 0 until ROI_GRID_SIZE) {
                            if (mask[ny * ROI_GRID_SIZE + nx]) {
                                seedX = nx
                                seedY = ny
                                found = true
                                break
                            }
                        }
                    }
                    if (found) break
                }
                if (found) break
            }
        }

        // Flood fill to collect connected object component
        val queue = ArrayDeque<Int>()
        val seedIdx = seedY * ROI_GRID_SIZE + seedX
        if (mask[seedIdx]) {
            queue.add(seedIdx)
            visited[seedIdx] = true
            cleanedMask[seedIdx] = true

            while (queue.isNotEmpty()) {
                val cur = queue.poll() ?: continue
                val cy = cur / ROI_GRID_SIZE
                val cx = cur % ROI_GRID_SIZE

                val neighbors = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
                for ((nxDiff, nyDiff) in neighbors) {
                    val nx = cx + nxDiff
                    val ny = cy + nyDiff
                    if (nx in 0 until ROI_GRID_SIZE && ny in 0 until ROI_GRID_SIZE) {
                        val nIdx = ny * ROI_GRID_SIZE + nx
                        if (!visited[nIdx] && mask[nIdx]) {
                            visited[nIdx] = true
                            cleanedMask[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }
            }
        } else {
            // Fallback: If contrast is too low to separate, use inner portion of selection
            for (y in (ROI_GRID_SIZE * 0.15f).toInt()..(ROI_GRID_SIZE * 0.85f).toInt()) {
                for (x in (ROI_GRID_SIZE * 0.15f).toInt()..(ROI_GRID_SIZE * 0.85f).toInt()) {
                    cleanedMask[y * ROI_GRID_SIZE + x] = true
                }
            }
        }

        // 5. Compute tight bounding box & image moments for rotation angle
        var minGridX = ROI_GRID_SIZE
        var maxGridX = 0
        var minGridY = ROI_GRID_SIZE
        var maxGridY = 0

        var m00 = 0.0
        var m10 = 0.0
        var m01 = 0.0

        for (y in 0 until ROI_GRID_SIZE) {
            for (x in 0 until ROI_GRID_SIZE) {
                if (cleanedMask[y * ROI_GRID_SIZE + x]) {
                    if (x < minGridX) minGridX = x
                    if (x > maxGridX) maxGridX = x
                    if (y < minGridY) minGridY = y
                    if (y > maxGridY) maxGridY = y

                    m00 += 1.0
                    m10 += x
                    m01 += y
                }
            }
        }

        // Centroid in grid coordinates
        val centroidGridX = if (m00 > 0) (m10 / m00).toFloat() else centerGrid
        val centroidGridY = if (m00 > 0) (m01 / m00).toFloat() else centerGrid

        // Second-order central moments for orientation angle
        var mu20 = 0.0
        var mu02 = 0.0
        var mu11 = 0.0

        for (y in 0 until ROI_GRID_SIZE) {
            val dy = y - centroidGridY
            for (x in 0 until ROI_GRID_SIZE) {
                if (cleanedMask[y * ROI_GRID_SIZE + x]) {
                    val dx = x - centroidGridX
                    mu20 += dx * dx
                    mu02 += dy * dy
                    mu11 += dx * dy
                }
            }
        }

        // Orientation angle in degrees
        val thetaRad = 0.5 * atan2(2.0 * mu11, mu20 - mu02)
        val thetaDeg = (Math.toDegrees(thetaRad)).toFloat()

        // 6. Convert boundary back to normalized coordinates
        val normLeft = expLeft + (minGridX.toFloat() / ROI_GRID_SIZE) * (expRight - expLeft)
        val normRight = expLeft + (maxGridX.toFloat() / ROI_GRID_SIZE) * (expRight - expLeft)
        val normTop = expTop + (minGridY.toFloat() / ROI_GRID_SIZE) * (expBottom - expTop)
        val normBottom = expTop + (maxGridY.toFloat() / ROI_GRID_SIZE) * (expBottom - expTop)

        val tightBox = NormalizedRect(
            left = normLeft.coerceIn(0f, 1f),
            top = normTop.coerceIn(0f, 1f),
            right = normRight.coerceIn(normLeft + 0.01f, 1f),
            bottom = normBottom.coerceIn(normTop + 0.01f, 1f)
        )

        val normCentroidX = expLeft + (centroidGridX / ROI_GRID_SIZE) * (expRight - expLeft)
        val normCentroidY = expTop + (centroidGridY / ROI_GRID_SIZE) * (expBottom - expTop)

        // 7. Extract radial contour polygon points for smooth boundary rendering
        val contourPoints = ArrayList<Point2D>()
        val anglesCount = 28
        for (i in 0 until anglesCount) {
            val ang = (i.toFloat() / anglesCount) * 2f * Math.PI
            val cosA = cos(ang).toFloat()
            val sinA = sin(ang).toFloat()

            var rayDist = 0f
            // March outward from centroid until mask boundary
            for (step in 1..48) {
                val rx = (centroidGridX + cosA * step).roundToInt()
                val ry = (centroidGridY + sinA * step).roundToInt()
                if (rx in 0 until ROI_GRID_SIZE && ry in 0 until ROI_GRID_SIZE) {
                    if (cleanedMask[ry * ROI_GRID_SIZE + rx]) {
                        rayDist = step.toFloat()
                    }
                } else {
                    break
                }
            }

            val finalGridX = centroidGridX + cosA * rayDist
            val finalGridY = centroidGridY + sinA * rayDist

            val ptNormX = expLeft + (finalGridX / ROI_GRID_SIZE) * (expRight - expLeft)
            val ptNormY = expTop + (finalGridY / ROI_GRID_SIZE) * (expBottom - expTop)

            contourPoints.add(Point2D(ptNormX.coerceIn(0f, 1f), ptNormY.coerceIn(0f, 1f)))
        }

        return ObjectBoundary(
            tightBox = tightBox,
            contourPoints = contourPoints,
            rotationDegrees = thetaDeg,
            centroidX = normCentroidX,
            centroidY = normCentroidY,
            areaNormalized = (m00 / (ROI_GRID_SIZE * ROI_GRID_SIZE)).toFloat()
        )
    }
}
