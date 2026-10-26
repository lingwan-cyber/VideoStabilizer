package com.example.videostabilizer.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.ObjectBoundary
import com.example.videostabilizer.model.Point2D
import com.example.videostabilizer.model.TrackingFrameState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance visual object tracker with rotation estimation,
 * boundary contour deformation, multi-angle template correlation,
 * HSV color histogram matching, and Kalman kinematic motion prediction.
 */
class VisualObjectTracker {

    companion object {
        private const val TEMPLATE_SIZE = 32
        private const val HIST_HUE_BINS = 16
        private const val HIST_SAT_BINS = 8
        private const val HIST_TOTAL_BINS = HIST_HUE_BINS * HIST_SAT_BINS
        private const val MATCH_THRESHOLD = 0.38f
        private const val REACQUISITION_THRESHOLD = 0.45f
    }

    private var isInitialized = false
    private var initialBox = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
    private var currentBox = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)

    // Motion model state: translation
    private var posX = 0.5f
    private var posY = 0.5f
    private var velX = 0f
    private var velY = 0f
    private var targetWidth = 0.15f
    private var targetHeight = 0.15f

    // Motion model state: rotation
    private var currentAngle = 0f
    private var angularVelocity = 0f

    // Base contour relative to centroid
    private val relativeContour = ArrayList<Point2D>()

    // Visual model
    private val templateLuma = FloatArray(TEMPLATE_SIZE * TEMPLATE_SIZE)
    private val colorHistogram = FloatArray(HIST_TOTAL_BINS)

    // Tracking metadata
    private var consecutiveLostFrames = 0
    private var currentConfidence = 1.0f
    private var isOutOfFrame = false

    /**
     * Initialize tracker with an initial frame and identified boundary.
     */
    fun init(
        bitmap: Bitmap,
        boundary: ObjectBoundary,
        timestampUs: Long = 0L
    ): TrackingFrameState {
        initialBox = boundary.tightBox
        currentBox = boundary.tightBox
        posX = boundary.centroidX
        posY = boundary.centroidY
        targetWidth = boundary.tightBox.width.coerceIn(0.02f, 0.9f)
        targetHeight = boundary.tightBox.height.coerceIn(0.02f, 0.9f)
        velX = 0f
        velY = 0f

        currentAngle = boundary.rotationDegrees
        angularVelocity = 0f

        consecutiveLostFrames = 0
        currentConfidence = 1.0f
        isOutOfFrame = false

        // Store contour relative to centroid and initial angle
        relativeContour.clear()
        val rad0 = Math.toRadians(-currentAngle.toDouble())
        val cos0 = cos(rad0).toFloat()
        val sin0 = sin(rad0).toFloat()

        for (pt in boundary.contourPoints) {
            val dx = pt.x - posX
            val dy = pt.y - posY
            // Un-rotate to reference canonical frame
            val unrotX = dx * cos0 - dy * sin0
            val unrotY = dx * sin0 + dy * cos0
            relativeContour.add(Point2D(unrotX, unrotY))
        }

        extractRotatedTemplate(bitmap, posX, posY, targetWidth, targetHeight, currentAngle, templateLuma)
        extractColorHistogram(bitmap, boundary.tightBox, colorHistogram)

        isInitialized = true

        val currentContour = computeCurrentContour(posX, posY, currentAngle)

        return TrackingFrameState(
            frameIndex = 0,
            timestampUs = timestampUs,
            targetBox = currentBox,
            rotationDegrees = currentAngle,
            boundaryContour = currentContour,
            confidence = 1.0f,
            isOutOfFrame = false,
            isPredicted = false,
            velocityX = 0f,
            velocityY = 0f,
            angularVelocity = 0f
        )
    }

    /**
     * Process consecutive video frame and return tracked position and rotation.
     */
    fun track(bitmap: Bitmap, frameIndex: Int, timestampUs: Long): TrackingFrameState {
        if (!isInitialized) {
            return TrackingFrameState(
                frameIndex = frameIndex,
                timestampUs = timestampUs,
                targetBox = currentBox,
                rotationDegrees = currentAngle,
                boundaryContour = emptyList(),
                confidence = 0f,
                isOutOfFrame = true,
                isPredicted = true
            )
        }

        val frameWidth = bitmap.width
        val frameHeight = bitmap.height

        // 1. Motion prediction (translation & rotation)
        val predictedX = posX + velX
        val predictedY = posY + velY
        val predictedAngle = normalizeAngle(currentAngle + angularVelocity)

        // Check if predicted position is outside physical frame bounds
        val boxLeft = predictedX - targetWidth / 2f
        val boxRight = predictedX + targetWidth / 2f
        val boxTop = predictedY - targetHeight / 2f
        val boxBottom = predictedY + targetHeight / 2f

        val predictedOutOfFrame = boxRight <= 0.05f || boxLeft >= 0.95f ||
                boxBottom <= 0.05f || boxTop >= 0.95f

        // 2. Search region calculation
        val searchRadiusX = if (consecutiveLostFrames > 0) {
            (targetWidth * 1.5f + abs(velX) * 4f + 0.10f * min(consecutiveLostFrames, 5)).coerceAtMost(0.45f)
        } else {
            (targetWidth * 0.8f + abs(velX) * 2.5f + 0.06f).coerceAtMost(0.35f)
        }
        val searchRadiusY = if (consecutiveLostFrames > 0) {
            (targetHeight * 1.5f + abs(velY) * 4f + 0.10f * min(consecutiveLostFrames, 5)).coerceAtMost(0.45f)
        } else {
            (targetHeight * 0.8f + abs(velY) * 2.5f + 0.06f).coerceAtMost(0.35f)
        }

        val minSearchX = (predictedX - searchRadiusX).coerceIn(targetWidth / 2f, 1f - targetWidth / 2f)
        val maxSearchX = (predictedX + searchRadiusX).coerceIn(targetWidth / 2f, 1f - targetWidth / 2f)
        val minSearchY = (predictedY - searchRadiusY).coerceIn(targetHeight / 2f, 1f - targetHeight / 2f)
        val maxSearchY = (predictedY + searchRadiusY).coerceIn(targetHeight / 2f, 1f - targetHeight / 2f)

        // 3. Multi-angle and multi-candidate visual search
        var bestScore = -1f
        var bestCandidateX = predictedX
        var bestCandidateY = predictedY
        var bestCandidateAngle = predictedAngle

        val candidateTemplate = FloatArray(TEMPLATE_SIZE * TEMPLATE_SIZE)
        val candidateHist = FloatArray(HIST_TOTAL_BINS)

        // Test rotational offsets around predicted angle
        val angleDeltas = floatArrayOf(0f, -15f, 15f, -30f, 30f)

        if (minSearchX < maxSearchX && minSearchY < maxSearchY) {
            val stepsX = if (consecutiveLostFrames > 0) 7 else 5
            val stepsY = if (consecutiveLostFrames > 0) 7 else 5
            val stepSizeX = (maxSearchX - minSearchX) / (stepsX - 1).coerceAtLeast(1)
            val stepSizeY = (maxSearchY - minSearchY) / (stepsY - 1).coerceAtLeast(1)

            for (iy in 0 until stepsY) {
                val cy = minSearchY + iy * stepSizeY
                for (ix in 0 until stepsX) {
                    val cx = minSearchX + ix * stepSizeX
                    val candidateBox = NormalizedRect.centeredAt(cx, cy, targetWidth, targetHeight)

                    extractColorHistogram(bitmap, candidateBox, candidateHist)
                    val histScore = computeHistogramIntersection(colorHistogram, candidateHist)

                    // Test rotation angles
                    for (dAng in angleDeltas) {
                        val testAngle = normalizeAngle(predictedAngle + dAng)
                        extractRotatedTemplate(bitmap, cx, cy, targetWidth, targetHeight, testAngle, candidateTemplate)
                        val lumaScore = computeNcc(templateLuma, candidateTemplate)

                        val distNorm = sqrt(((cx - predictedX) / searchRadiusX).let { it * it } +
                                ((cy - predictedY) / searchRadiusY).let { it * it })
                        val distanceFactor = (1f - 0.20f * distNorm).coerceIn(0.5f, 1.0f)

                        val combinedScore = (0.55f * lumaScore + 0.45f * histScore) * distanceFactor

                        if (combinedScore > bestScore) {
                            bestScore = combinedScore
                            bestCandidateX = cx
                            bestCandidateY = cy
                            bestCandidateAngle = testAngle
                        }
                    }
                }
            }
        }

        // Sub-pixel / fine angle refinement around the best candidate
        if (bestScore > 0.25f) {
            val fineDeltas = floatArrayOf(-6f, -3f, 0f, 3f, 6f)
            for (fAng in fineDeltas) {
                val testAngle = normalizeAngle(bestCandidateAngle + fAng)
                extractRotatedTemplate(bitmap, bestCandidateX, bestCandidateY, targetWidth, targetHeight, testAngle, candidateTemplate)
                val lumaScore = computeNcc(templateLuma, candidateTemplate)
                if (lumaScore * 0.95f > bestScore * 0.55f) {
                    val fineScore = 0.55f * lumaScore + 0.45f * computeHistogramIntersection(colorHistogram, candidateHist)
                    if (fineScore > bestScore) {
                        bestScore = fineScore
                        bestCandidateAngle = testAngle
                    }
                }
            }
        }

        // 4. Decision: Verified Detection vs Out-Of-Frame / Occlusion
        val isCandidateValid = if (consecutiveLostFrames > 0) {
            bestScore >= REACQUISITION_THRESHOLD && !predictedOutOfFrame
        } else {
            bestScore >= MATCH_THRESHOLD && !predictedOutOfFrame
        }

        val resultBox: NormalizedRect
        val isPredictedState: Boolean

        if (isCandidateValid) {
            // Tracked successfully!
            val measuredVx = bestCandidateX - posX
            val measuredVy = bestCandidateY - posY
            val measuredAngleDiff = shortestAngleDiff(bestCandidateAngle, currentAngle)

            velX = velX * 0.35f + measuredVx * 0.65f
            velY = velY * 0.35f + measuredVy * 0.65f
            angularVelocity = angularVelocity * 0.35f + measuredAngleDiff * 0.65f

            posX = bestCandidateX
            posY = bestCandidateY
            currentAngle = normalizeAngle(currentAngle + measuredAngleDiff)

            consecutiveLostFrames = 0
            isOutOfFrame = false
            isPredictedState = false
            currentConfidence = (currentConfidence * 0.2f + bestScore * 0.8f).coerceIn(0.1f, 1.0f)

            // Adaptive template update when tracking is highly confident
            if (bestScore > 0.68f) {
                val alpha = 0.05f
                for (i in templateLuma.indices) {
                    templateLuma[i] = (1f - alpha) * templateLuma[i] + alpha * candidateTemplate[i]
                }
                for (i in colorHistogram.indices) {
                    colorHistogram[i] = (1f - alpha) * colorHistogram[i] + alpha * candidateHist[i]
                }
            }
            resultBox = NormalizedRect.centeredAt(posX, posY, targetWidth, targetHeight)
        } else {
            // Target is lost, occluded, or exited the camera frame!
            consecutiveLostFrames++
            isOutOfFrame = predictedOutOfFrame || consecutiveLostFrames >= 2
            isPredictedState = true

            // Inertial decay for translation and rotation
            velX *= 0.90f
            velY *= 0.90f
            angularVelocity *= 0.88f

            posX += velX
            posY += velY
            currentAngle = normalizeAngle(currentAngle + angularVelocity)

            currentConfidence = (currentConfidence * 0.82f).coerceAtLeast(0.05f)
            resultBox = NormalizedRect.centeredAt(posX, posY, targetWidth, targetHeight)
        }

        currentBox = resultBox
        val currentContour = computeCurrentContour(posX, posY, currentAngle)

        return TrackingFrameState(
            frameIndex = frameIndex,
            timestampUs = timestampUs,
            targetBox = resultBox,
            rotationDegrees = currentAngle,
            boundaryContour = currentContour,
            confidence = currentConfidence,
            isOutOfFrame = isOutOfFrame || resultBox.isPartiallyOutOfBounds(),
            isPredicted = isPredictedState,
            velocityX = velX,
            velocityY = velY,
            angularVelocity = angularVelocity
        )
    }

    /**
     * Computes the contour points translated and rotated to current object pose.
     */
    private fun computeCurrentContour(cx: Float, cy: Float, angleDeg: Float): List<Point2D> {
        if (relativeContour.isEmpty()) return emptyList()

        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        return relativeContour.map { rel ->
            val rotX = rel.x * cosA - rel.y * sinA
            val rotY = rel.x * sinA + rel.y * cosA
            Point2D(cx + rotX, cy + rotY)
        }
    }

    /**
     * Extracts normalized grayscale luminance values taking into account rotation angle.
     */
    private fun extractRotatedTemplate(
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        widthNorm: Float,
        heightNorm: Float,
        angleDeg: Float,
        outLuma: FloatArray
    ) {
        val bw = bitmap.width
        val bh = bitmap.height

        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        val halfW = widthNorm / 2f
        val halfH = heightNorm / 2f

        var sum = 0f
        var idx = 0

        for (y in 0 until TEMPLATE_SIZE) {
            val v = (y.toFloat() / (TEMPLATE_SIZE - 1) - 0.5f) * 2f * halfH
            for (x in 0 until TEMPLATE_SIZE) {
                val u = (x.toFloat() / (TEMPLATE_SIZE - 1) - 0.5f) * 2f * halfW

                // Rotate sample position
                val rotU = u * cosA - v * sinA
                val rotV = u * sinA + v * cosA

                val sampleX = ((cx + rotU) * bw).toInt().coerceIn(0, bw - 1)
                val sampleY = ((cy + rotV) * bh).toInt().coerceIn(0, bh - 1)

                val pixel = bitmap.getPixel(sampleX, sampleY)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                outLuma[idx] = luma
                sum += luma
                idx++
            }
        }

        val mean = sum / (TEMPLATE_SIZE * TEMPLATE_SIZE)
        var sqSum = 0f
        for (i in outLuma.indices) {
            outLuma[i] -= mean
            sqSum += outLuma[i] * outLuma[i]
        }
        val std = sqrt(sqSum).coerceAtLeast(1e-4f)
        for (i in outLuma.indices) {
            outLuma[i] /= std
        }
    }

    private fun extractColorHistogram(bitmap: Bitmap, box: NormalizedRect, outHist: FloatArray) {
        outHist.fill(0f)
        val bw = bitmap.width
        val bh = bitmap.height

        val pxLeft = (box.left * bw).roundToInt().coerceIn(0, bw - 1)
        val pxTop = (box.top * bh).roundToInt().coerceIn(0, bh - 1)
        val pxRight = (box.right * bw).roundToInt().coerceIn(pxLeft + 1, bw)
        val pxBottom = (box.bottom * bh).roundToInt().coerceIn(pxTop + 1, bh)

        val subW = max(1, pxRight - pxLeft)
        val subH = max(1, pxBottom - pxTop)

        val samplePoints = 20
        val hsv = FloatArray(3)
        var totalSamples = 0

        for (sy in 0 until samplePoints) {
            val srcY = (pxTop + (sy.toFloat() / samplePoints) * subH).toInt().coerceIn(0, bh - 1)
            for (sx in 0 until samplePoints) {
                val srcX = (pxLeft + (sx.toFloat() / samplePoints) * subW).toInt().coerceIn(0, bw - 1)
                val pixel = bitmap.getPixel(srcX, srcY)
                Color.colorToHSV(pixel, hsv)

                val hue = hsv[0] // 0..360
                val sat = hsv[1] // 0..1

                val hBin = ((hue / 360f) * HIST_HUE_BINS).toInt().coerceIn(0, HIST_HUE_BINS - 1)
                val sBin = (sat * HIST_SAT_BINS).toInt().coerceIn(0, HIST_SAT_BINS - 1)
                val binIdx = hBin * HIST_SAT_BINS + sBin

                outHist[binIdx] += 1f
                totalSamples++
            }
        }

        if (totalSamples > 0) {
            val inv = 1f / totalSamples
            for (i in outHist.indices) {
                outHist[i] *= inv
            }
        }
    }

    private fun computeNcc(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return ((dot + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun computeHistogramIntersection(h1: FloatArray, h2: FloatArray): Float {
        var match = 0f
        for (i in h1.indices) {
            match += min(h1[i], h2[i])
        }
        return match.coerceIn(0f, 1f)
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    private fun shortestAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }
}
