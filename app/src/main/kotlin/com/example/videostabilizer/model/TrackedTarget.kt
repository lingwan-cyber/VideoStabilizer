package com.example.videostabilizer.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * 2D point representation with pure Kotlin value semantics (JVM unit-test friendly).
 */
data class Point2D(val x: Float, val y: Float) {
    fun toPointF(): PointF = PointF(x, y)

    companion object {
        fun fromPointF(pointF: PointF): Point2D = Point2D(pointF.x, pointF.y)
    }
}

/**
 * Normalized bounding box where coordinates are in [0.0, 1.0].
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.001f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.001f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun toPixelRect(frameWidth: Int, frameHeight: Int): RectF {
        return RectF(
            left * frameWidth,
            top * frameHeight,
            right * frameWidth,
            bottom * frameHeight
        )
    }

    fun isPartiallyOutOfBounds(): Boolean {
        return left < 0f || top < 0f || right > 1f || bottom > 1f
    }

    fun isFullyOutOfBounds(): Boolean {
        return right <= 0f || left >= 1f || bottom <= 0f || top >= 1f
    }

    companion object {
        fun fromPixelRect(rect: RectF, frameWidth: Int, frameHeight: Int): NormalizedRect {
            val w = frameWidth.coerceAtLeast(1).toFloat()
            val h = frameHeight.coerceAtLeast(1).toFloat()
            return NormalizedRect(
                left = (rect.left / w).coerceIn(0f, 1f),
                top = (rect.top / h).coerceIn(0f, 1f),
                right = (rect.right / w).coerceIn(0f, 1f),
                bottom = (rect.bottom / h).coerceIn(0f, 1f)
            )
        }

        fun centeredAt(cx: Float, cy: Float, width: Float, height: Float): NormalizedRect {
            val halfW = width / 2f
            val halfH = height / 2f
            return NormalizedRect(
                left = cx - halfW,
                top = cy - halfH,
                right = cx + halfW,
                bottom = cy + halfH
            )
        }
    }
}

/**
 * Identified object boundary and contour.
 */
data class ObjectBoundary(
    val tightBox: NormalizedRect,
    val contourPoints: List<Point2D>, // normalized polygon coordinates
    val rotationDegrees: Float,
    val centroidX: Float,
    val centroidY: Float,
    val areaNormalized: Float
)

/**
 * Tracking status and state for a specific video frame, including rotation and boundary.
 */
data class TrackingFrameState(
    val frameIndex: Int,
    val timestampUs: Long,
    val targetBox: NormalizedRect,
    val rotationDegrees: Float = 0f,
    val boundaryContour: List<Point2D> = emptyList(),
    val confidence: Float,
    val isOutOfFrame: Boolean,
    val isPredicted: Boolean,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val angularVelocity: Float = 0f
)

/**
 * Stabilization modes.
 */
enum class StabilizationMode(val title: String, val description: String) {
    STRICT_CENTER(
        "Strict Center",
        "Locks the selected object directly at the center of the frame."
    ),
    CINEMATIC_FOLLOW(
        "Cinematic Follow",
        "Smoothly follows the object with cinematic inertia and deadzone."
    ),
    EXTREME_STABILIZE(
        "Rock Steady",
        "Aggressively dampens shakes while keeping the object in view."
    )
}

/**
 * Rotation stabilization behavior.
 */
enum class RotationStabilizationMode(val title: String, val description: String) {
    UPRIGHT_LEVEL(
        "Level Camera",
        "Keeps camera level while object rotates freely in the center."
    ),
    FOLLOW_OBJECT_ROTATION(
        "Lock Object Horizon",
        "Inversely rotates camera to keep the spinning object upright."
    ),
    SMOOTH_ROTATION(
        "Smooth Rotation",
        "Smooths camera roll to follow rotation without jarring snaps."
    )
}

/**
 * Strategy when the object exits the frame or is occluded.
 */
enum class OutOfFrameStrategy(val title: String) {
    INERTIAL_COAST("Inertial Coast & Hold"),
    SMOOTH_RESET("Glide to Center"),
    EDGE_ANCHOR("Anchor at Boundary")
}

/**
 * User-configurable parameters for stabilization.
 */
data class StabilizationConfig(
    val zoomFactor: Float = 1.30f, // 1.1x to 2.2x
    val smoothingRadius: Int = 12, // window size in frames
    val deadzoneRadius: Float = 0.06f, // normalized deadzone radius
    val mode: StabilizationMode = StabilizationMode.CINEMATIC_FOLLOW,
    val rotationMode: RotationStabilizationMode = RotationStabilizationMode.UPRIGHT_LEVEL,
    val outOfFrameStrategy: OutOfFrameStrategy = OutOfFrameStrategy.INERTIAL_COAST
)

/**
 * Calculated transformation to apply to a frame for stabilization.
 */
data class FrameStabilizationTransform(
    val frameIndex: Int,
    val cropCenter: Point2D, // Normalized center [0..1]
    val zoom: Float,
    val cameraRotationDegrees: Float = 0f,
    val targetBox: NormalizedRect?,
    val targetRotationDegrees: Float = 0f,
    val boundaryContour: List<Point2D> = emptyList(),
    val isOutOfFrame: Boolean,
    val confidence: Float
)
