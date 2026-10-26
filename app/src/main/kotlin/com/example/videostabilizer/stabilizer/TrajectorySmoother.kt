package com.example.videostabilizer.stabilizer

import com.example.videostabilizer.model.FrameStabilizationTransform
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.OutOfFrameStrategy
import com.example.videostabilizer.model.Point2D
import com.example.videostabilizer.model.RotationStabilizationMode
import com.example.videostabilizer.model.StabilizationConfig
import com.example.videostabilizer.model.StabilizationMode
import com.example.videostabilizer.model.TrackingFrameState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Computes smooth, stabilized camera trajectories (pan, tilt, and rotation)
 * to keep the tracked target centered without camera jitter and handling edge clamping
 * and object rotation during out-of-frame events.
 */
class TrajectorySmoother {

    /**
     * Compute full-sequence smoothed stabilization transforms for all frames.
     */
    fun computeStabilizationSequence(
        trackingStates: List<TrackingFrameState>,
        config: StabilizationConfig
    ): List<FrameStabilizationTransform> {
        if (trackingStates.isEmpty()) return emptyList()

        val count = trackingStates.size
        val zoom = config.zoomFactor.coerceAtLeast(1.05f)
        val halfCropW = 0.5f / zoom
        val halfCropH = 0.5f / zoom

        val minCenterX = halfCropW
        val maxCenterX = 1f - halfCropW
        val minCenterY = halfCropH
        val maxCenterY = 1f - halfCropH

        // 1. Raw target centers and raw angles with out-of-frame handling
        val rawCenters = ArrayList<Point2D>(count)
        val rawAngles = FloatArray(count)

        var lastValidCenter = Point2D(0.5f, 0.5f)
        var lastValidAngle = 0f
        var coastingVelX = 0f
        var coastingVelY = 0f
        var coastingAngVel = 0f

        for (i in 0 until count) {
            val state = trackingStates[i]
            val box = state.targetBox

            val center: Point2D
            val angle: Float

            if (!state.isOutOfFrame && state.confidence >= 0.25f) {
                center = Point2D(box.centerX, box.centerY)
                angle = state.rotationDegrees
                lastValidCenter = center
                lastValidAngle = angle
                coastingVelX = state.velocityX
                coastingVelY = state.velocityY
                coastingAngVel = state.angularVelocity
            } else {
                coastingAngVel *= 0.88f
                angle = lastValidAngle + coastingAngVel
                lastValidAngle = angle

                center = when (config.outOfFrameStrategy) {
                    OutOfFrameStrategy.INERTIAL_COAST -> {
                        coastingVelX *= 0.88f
                        coastingVelY *= 0.88f
                        Point2D(
                            lastValidCenter.x + coastingVelX,
                            lastValidCenter.y + coastingVelY
                        )
                    }
                    OutOfFrameStrategy.SMOOTH_RESET -> {
                        Point2D(
                            lastValidCenter.x * 0.92f + 0.5f * 0.08f,
                            lastValidCenter.y * 0.92f + 0.5f * 0.08f
                        )
                    }
                    OutOfFrameStrategy.EDGE_ANCHOR -> {
                        lastValidCenter
                    }
                }
                lastValidCenter = center
            }
            rawCenters.add(center)
            rawAngles[i] = angle
        }

        // 2. Smooth center trajectory based on selected mode
        val smoothedCenters = ArrayList<Point2D>(count)

        when (config.mode) {
            StabilizationMode.STRICT_CENTER -> {
                val radius = 3
                for (i in 0 until count) {
                    var sumX = 0f
                    var sumY = 0f
                    var weightSum = 0f
                    for (k in -radius..radius) {
                        val idx = (i + k).coerceIn(0, count - 1)
                        val w = exp(-0.5 * (k * k) / (1.5 * 1.5)).toFloat()
                        sumX += rawCenters[idx].x * w
                        sumY += rawCenters[idx].y * w
                        weightSum += w
                    }
                    smoothedCenters.add(Point2D(sumX / weightSum, sumY / weightSum))
                }
            }

            StabilizationMode.CINEMATIC_FOLLOW -> {
                var currentCamX = rawCenters[0].x
                var currentCamY = rawCenters[0].y
                var camVelX = 0f
                var camVelY = 0f
                val deadzone = config.deadzoneRadius

                for (i in 0 until count) {
                    val target = rawCenters[i]
                    val dx = target.x - currentCamX
                    val dy = target.y - currentCamY
                    val dist = hypot(dx, dy)

                    if (dist > deadzone) {
                        val excessDist = dist - deadzone
                        val dirX = dx / dist
                        val dirY = dy / dist

                        val forceX = dirX * excessDist * 0.35f
                        val forceY = dirY * excessDist * 0.35f

                        camVelX = (camVelX * 0.65f) + forceX
                        camVelY = (camVelY * 0.65f) + forceY
                    } else {
                        camVelX *= 0.60f
                        camVelY *= 0.60f
                    }

                    currentCamX += camVelX
                    currentCamY += camVelY

                    smoothedCenters.add(Point2D(currentCamX, currentCamY))
                }
            }

            StabilizationMode.EXTREME_STABILIZE -> {
                val radius = config.smoothingRadius.coerceIn(5, 40)
                val sigma = radius / 2.5f

                for (i in 0 until count) {
                    var sumX = 0f
                    var sumY = 0f
                    var weightSum = 0f

                    for (k in -radius..radius) {
                        val idx = (i + k).coerceIn(0, count - 1)
                        val w = exp(-0.5 * (k * k) / (sigma * sigma)).toFloat()
                        sumX += rawCenters[idx].x * w
                        sumY += rawCenters[idx].y * w
                        weightSum += w
                    }

                    smoothedCenters.add(Point2D(sumX / weightSum, sumY / weightSum))
                }
            }
        }

        // 3. Smooth rotation angle
        val smoothedAngles = FloatArray(count)
        var unrolledAngle = rawAngles[0]
        val continuousAngles = FloatArray(count)
        continuousAngles[0] = unrolledAngle

        // Unroll angle discontinuities across +/-180 boundary
        for (i in 1 until count) {
            var diff = rawAngles[i] - rawAngles[i - 1]
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            unrolledAngle += diff
            continuousAngles[i] = unrolledAngle
        }

        // Gaussian smoothing over continuous angles
        val rotRadius = 5
        for (i in 0 until count) {
            var sum = 0f
            var wSum = 0f
            for (k in -rotRadius..rotRadius) {
                val idx = (i + k).coerceIn(0, count - 1)
                val w = exp(-0.5 * (k * k) / (2.5 * 2.5)).toFloat()
                sum += continuousAngles[idx] * w
                wSum += w
            }
            smoothedAngles[i] = sum / wSum
        }

        // 4. Construct final transforms
        val result = ArrayList<FrameStabilizationTransform>(count)
        for (i in 0 until count) {
            val state = trackingStates[i]
            val smoothed = smoothedCenters[i]

            val clampedX = smoothed.x.coerceIn(minCenterX, maxCenterX)
            val clampedY = smoothed.y.coerceIn(minCenterY, maxCenterY)

            // Camera rotation to apply:
            val camRotation = when (config.rotationMode) {
                RotationStabilizationMode.UPRIGHT_LEVEL -> 0f
                RotationStabilizationMode.FOLLOW_OBJECT_ROTATION -> -smoothedAngles[i]
                RotationStabilizationMode.SMOOTH_ROTATION -> -smoothedAngles[i] * 0.7f
            }

            result.add(
                FrameStabilizationTransform(
                    frameIndex = state.frameIndex,
                    cropCenter = Point2D(clampedX, clampedY),
                    zoom = zoom,
                    cameraRotationDegrees = camRotation,
                    targetBox = state.targetBox,
                    targetRotationDegrees = state.rotationDegrees,
                    boundaryContour = state.boundaryContour,
                    isOutOfFrame = state.isOutOfFrame,
                    confidence = state.confidence
                )
            )
        }

        return result
    }
}
