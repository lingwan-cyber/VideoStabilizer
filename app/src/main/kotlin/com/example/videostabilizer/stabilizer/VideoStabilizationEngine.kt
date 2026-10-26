package com.example.videostabilizer.stabilizer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.videostabilizer.ai.ObjectBoundaryDetector
import com.example.videostabilizer.ai.VisualObjectTracker
import com.example.videostabilizer.model.FrameStabilizationTransform
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.ObjectBoundary
import com.example.videostabilizer.model.StabilizationConfig
import com.example.videostabilizer.model.TrackingFrameState
import com.example.videostabilizer.video.VideoFrameExtractor
import com.example.videostabilizer.video.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level coordinator that runs boundary identification,
 * AI rotation-aware tracking across video frames, and applies stabilization smoothing.
 */
class VideoStabilizationEngine(
    private val context: Context,
    private val frameExtractor: VideoFrameExtractor = VideoFrameExtractor(context),
    private val boundaryDetector: ObjectBoundaryDetector = ObjectBoundaryDetector(),
    private val trajectorySmoother: TrajectorySmoother = TrajectorySmoother()
) {

    data class AnalysisResult(
        val metadata: VideoMetadata,
        val initialBoundary: ObjectBoundary,
        val trackingStates: List<TrackingFrameState>,
        val transforms: List<FrameStabilizationTransform>
    )

    fun identifyBoundary(bitmap: Bitmap, selection: NormalizedRect): ObjectBoundary {
        return boundaryDetector.detectBoundary(bitmap, selection)
    }

    suspend fun analyzeAndStabilize(
        videoUri: Uri,
        initialSelection: NormalizedRect,
        config: StabilizationConfig,
        onProgress: (phase: String, progress: Float) -> Unit
    ): AnalysisResult = withContext(Dispatchers.Default) {
        onProgress("Reading metadata", 0.05f)
        val metadata = frameExtractor.getMetadata(videoUri)

        val tracker = VisualObjectTracker()
        val trackingStates = ArrayList<TrackingFrameState>()

        val fps = metadata.estimatedFps.coerceIn(15, 60)
        val frameDurationUs = 1_000_000L / fps
        val totalFrames = metadata.totalFrames.coerceIn(10, 1800)

        val analysisWidth = 360
        val analysisHeight = (analysisWidth * (metadata.height.toFloat() / metadata.width)).toInt()

        onProgress("Identifying object boundary and orientation", 0.08f)

        // Frame 0: Extract initial frame and identify actual boundary
        val firstFrame = frameExtractor.getFrameAtTime(videoUri, 0L, analysisWidth, analysisHeight)
            ?: throw IllegalStateException("Could not extract initial frame from video.")

        val initialBoundary = boundaryDetector.detectBoundary(firstFrame, initialSelection)
        val initState = tracker.init(firstFrame, initialBoundary, 0L)
        trackingStates.add(initState)

        // Track remaining frames
        val frameStep = 1
        for (frameIdx in 1 until totalFrames step frameStep) {
            val timestampUs = frameIdx * frameDurationUs
            val frameBitmap = frameExtractor.getFrameAtTime(videoUri, timestampUs, analysisWidth, analysisHeight)

            val state = if (frameBitmap != null) {
                tracker.track(frameBitmap, frameIdx, timestampUs)
            } else {
                val lastState = trackingStates.last()
                TrackingFrameState(
                    frameIndex = frameIdx,
                    timestampUs = timestampUs,
                    targetBox = lastState.targetBox,
                    rotationDegrees = lastState.rotationDegrees + lastState.angularVelocity,
                    boundaryContour = lastState.boundaryContour,
                    confidence = (lastState.confidence * 0.85f).coerceAtLeast(0.05f),
                    isOutOfFrame = true,
                    isPredicted = true,
                    velocityX = lastState.velocityX * 0.9f,
                    velocityY = lastState.velocityY * 0.9f,
                    angularVelocity = lastState.angularVelocity * 0.9f
                )
            }

            trackingStates.add(state)

            val pct = 0.10f + 0.75f * (frameIdx.toFloat() / totalFrames)
            onProgress("Tracking object & rotation (Frame $frameIdx / $totalFrames)", pct)
        }

        onProgress("Computing smooth camera & rotation trajectory", 0.90f)
        val transforms = trajectorySmoother.computeStabilizationSequence(trackingStates, config)

        onProgress("Stabilization complete", 1.0f)

        AnalysisResult(
            metadata = metadata,
            initialBoundary = initialBoundary,
            trackingStates = trackingStates,
            transforms = transforms
        )
    }

    fun recalculateWithConfig(
        trackingStates: List<TrackingFrameState>,
        config: StabilizationConfig
    ): List<FrameStabilizationTransform> {
        return trajectorySmoother.computeStabilizationSequence(trackingStates, config)
    }
}
