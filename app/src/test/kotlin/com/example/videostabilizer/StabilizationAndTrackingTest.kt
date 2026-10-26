package com.example.videostabilizer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.example.videostabilizer.ai.ObjectBoundaryDetector
import com.example.videostabilizer.ai.VisualObjectTracker
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.ObjectBoundary
import com.example.videostabilizer.model.OutOfFrameStrategy
import com.example.videostabilizer.model.RotationStabilizationMode
import com.example.videostabilizer.model.StabilizationConfig
import com.example.videostabilizer.model.StabilizationMode
import com.example.videostabilizer.model.TrackingFrameState
import com.example.videostabilizer.stabilizer.TrajectorySmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StabilizationAndTrackingTest {

    @Test
    fun testNormalizedRectGeometry() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f)
        assertEquals(0.4f, rect.width, 0.001f)
        assertEquals(0.4f, rect.height, 0.001f)
        assertEquals(0.4f, rect.centerX, 0.001f)
        assertEquals(0.5f, rect.centerY, 0.001f)
        assertFalse(rect.isPartiallyOutOfBounds())
        assertFalse(rect.isFullyOutOfBounds())

        val centered = NormalizedRect.centeredAt(0.5f, 0.5f, 0.2f, 0.2f)
        assertEquals(0.4f, centered.left, 0.001f)
        assertEquals(0.6f, centered.right, 0.001f)
        assertEquals(0.4f, centered.top, 0.001f)
        assertEquals(0.6f, centered.bottom, 0.001f)
    }

    @Test
    fun testNormalizedRectOutOfBoundsDetection() {
        val partialOut = NormalizedRect(-0.05f, 0.4f, 0.15f, 0.6f)
        assertTrue(partialOut.isPartiallyOutOfBounds())
        assertFalse(partialOut.isFullyOutOfBounds())

        val fullyOutRight = NormalizedRect(1.1f, 0.3f, 1.3f, 0.5f)
        assertTrue(fullyOutRight.isPartiallyOutOfBounds())
        assertTrue(fullyOutRight.isFullyOutOfBounds())

        val fullyOutTop = NormalizedRect(0.3f, -0.4f, 0.5f, -0.1f)
        assertTrue(fullyOutTop.isPartiallyOutOfBounds())
        assertTrue(fullyOutTop.isFullyOutOfBounds())
    }

    @Test
    fun testTrajectorySmootherNoBlackBorders() {
        val smoother = TrajectorySmoother()
        val zoom = 1.35f
        val config = StabilizationConfig(
            zoomFactor = zoom,
            mode = StabilizationMode.STRICT_CENTER
        )

        val halfCropW = 0.5f / zoom
        val halfCropH = 0.5f / zoom

        val trackingStates = (0..60).map { frameIdx ->
            val t = frameIdx / 60.0f
            val cx = 0.5f + 0.45f * kotlin.math.sin(t * 6.28f)
            val cy = 0.5f + 0.45f * kotlin.math.cos(t * 6.28f)
            TrackingFrameState(
                frameIndex = frameIdx,
                timestampUs = frameIdx * 33333L,
                targetBox = NormalizedRect.centeredAt(cx, cy, 0.1f, 0.1f),
                rotationDegrees = t * 180f,
                confidence = 0.9f,
                isOutOfFrame = false,
                isPredicted = false
            )
        }

        val transforms = smoother.computeStabilizationSequence(trackingStates, config)
        assertEquals(trackingStates.size, transforms.size)

        for (transform in transforms) {
            val center = transform.cropCenter
            assertTrue(
                "Crop window left edge < 0",
                center.x - halfCropW >= -1e-4f
            )
            assertTrue(
                "Crop window right edge > 1",
                center.x + halfCropW <= 1.0f + 1e-4f
            )
            assertTrue(
                "Crop window top edge < 0",
                center.y - halfCropH >= -1e-4f
            )
            assertTrue(
                "Crop window bottom edge > 1",
                center.y + halfCropH <= 1.0f + 1e-4f
            )
        }
    }

    @Test
    fun testRotationStabilizationModes() {
        val smoother = TrajectorySmoother()

        // 30 frames where object is rotating from 0 to 90 degrees
        val states = (0..30).map { i ->
            val angle = i * 3f
            TrackingFrameState(
                frameIndex = i,
                timestampUs = i * 33333L,
                targetBox = NormalizedRect.centeredAt(0.5f, 0.5f, 0.15f, 0.15f),
                rotationDegrees = angle,
                confidence = 0.95f,
                isOutOfFrame = false,
                isPredicted = false
            )
        }

        // Mode 1: UPRIGHT_LEVEL -> camera stays level (0 deg rotation)
        val levelConfig = StabilizationConfig(
            rotationMode = RotationStabilizationMode.UPRIGHT_LEVEL
        )
        val levelTransforms = smoother.computeStabilizationSequence(states, levelConfig)
        for (tr in levelTransforms) {
            assertEquals(0f, tr.cameraRotationDegrees, 0.001f)
        }

        // Mode 2: FOLLOW_OBJECT_ROTATION -> camera inversely rotates with object
        val followConfig = StabilizationConfig(
            rotationMode = RotationStabilizationMode.FOLLOW_OBJECT_ROTATION
        )
        val followTransforms = smoother.computeStabilizationSequence(states, followConfig)
        // Camera rotation should oppose the target rotation
        assertTrue(
            "Camera rotation did not oppose object rotation",
            followTransforms.last().cameraRotationDegrees < -60f
        )
    }

    @Test
    fun testOutOfFrameInertialCoastTrajectory() {
        val smoother = TrajectorySmoother()
        val config = StabilizationConfig(
            zoomFactor = 1.3f,
            mode = StabilizationMode.CINEMATIC_FOLLOW,
            outOfFrameStrategy = OutOfFrameStrategy.INERTIAL_COAST
        )

        val states = ArrayList<TrackingFrameState>()

        // Frames 0..19: Tracked and moving right
        for (i in 0..19) {
            val cx = 0.3f + i * 0.02f
            states.add(
                TrackingFrameState(
                    frameIndex = i,
                    timestampUs = i * 33333L,
                    targetBox = NormalizedRect.centeredAt(cx, 0.5f, 0.1f, 0.1f),
                    rotationDegrees = i * 2f,
                    confidence = 0.95f,
                    isOutOfFrame = false,
                    isPredicted = false,
                    velocityX = 0.02f,
                    velocityY = 0f,
                    angularVelocity = 2f
                )
            )
        }

        // Frames 20..35: Object goes OUT OF FRAME
        for (i in 20..35) {
            val cx = 0.7f + (i - 20) * 0.03f
            states.add(
                TrackingFrameState(
                    frameIndex = i,
                    timestampUs = i * 33333L,
                    targetBox = NormalizedRect.centeredAt(cx, 0.5f, 0.1f, 0.1f),
                    rotationDegrees = 40f + (i - 20) * 2f,
                    confidence = 0.15f,
                    isOutOfFrame = true,
                    isPredicted = true,
                    velocityX = 0.02f,
                    velocityY = 0f,
                    angularVelocity = 2f
                )
            )
        }

        // Frames 36..50: Object re-enters
        for (i in 36..50) {
            val cx = 0.8f - (i - 36) * 0.02f
            states.add(
                TrackingFrameState(
                    frameIndex = i,
                    timestampUs = i * 33333L,
                    targetBox = NormalizedRect.centeredAt(cx, 0.5f, 0.1f, 0.1f),
                    rotationDegrees = 70f,
                    confidence = 0.88f,
                    isOutOfFrame = false,
                    isPredicted = false,
                    velocityX = -0.02f,
                    velocityY = 0f
                )
            )
        }

        val transforms = smoother.computeStabilizationSequence(states, config)
        assertEquals(51, transforms.size)

        for (i in 1 until transforms.size) {
            val dx = abs(transforms[i].cropCenter.x - transforms[i - 1].cropCenter.x)
            val dy = abs(transforms[i].cropCenter.y - transforms[i - 1].cropCenter.y)
            assertTrue("Discontinuous camera jump at frame $i: dx=$dx", dx < 0.12f)
            assertTrue("Discontinuous camera jump at frame $i: dy=$dy", dy < 0.12f)
        }
    }

    @Test
    fun testShakeDampeningEffectiveness() {
        val smoother = TrajectorySmoother()
        val config = StabilizationConfig(
            zoomFactor = 1.4f,
            mode = StabilizationMode.EXTREME_STABILIZE,
            smoothingRadius = 15
        )

        val rawStates = (0..50).map { i ->
            val shake = if (i % 2 == 0) 0.06f else -0.06f
            val cx = 0.5f + shake
            TrackingFrameState(
                frameIndex = i,
                timestampUs = i * 33333L,
                targetBox = NormalizedRect.centeredAt(cx, 0.5f, 0.1f, 0.1f),
                rotationDegrees = 0f,
                confidence = 0.9f,
                isOutOfFrame = false,
                isPredicted = false
            )
        }

        val transforms = smoother.computeStabilizationSequence(rawStates, config)

        var rawJitter = 0f
        var stabilizedJitter = 0f

        for (i in 1..49) {
            rawJitter += abs(rawStates[i].targetBox.centerX - rawStates[i - 1].targetBox.centerX)
            stabilizedJitter += abs(transforms[i].cropCenter.x - transforms[i - 1].cropCenter.x)
        }

        assertTrue(
            "Stabilizer failed to dampen high frequency shake",
            stabilizedJitter < rawJitter * 0.25f
        )
    }
}
