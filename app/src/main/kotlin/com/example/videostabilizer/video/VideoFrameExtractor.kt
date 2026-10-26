package com.example.videostabilizer.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class VideoMetadata(
    val uri: Uri,
    val filePath: String?,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val rotation: Int,
    val estimatedFps: Int
) {
    val durationMs: Long get() = durationUs / 1000L
    val totalFrames: Int get() = ((durationUs / 1_000_000.0) * estimatedFps).toInt().coerceAtLeast(1)
}

class VideoFrameExtractor(private val context: Context) {

    suspend fun getMetadata(uri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val captureFpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

            var w = widthStr?.toIntOrNull() ?: 1280
            var h = heightStr?.toIntOrNull() ?: 720
            val rotation = rotationStr?.toIntOrNull() ?: 0

            // Adjust for rotation
            if (rotation == 90 || rotation == 270) {
                val tmp = w
                w = h
                h = tmp
            }

            val durationMs = durationStr?.toLongOrNull() ?: 5000L
            val durationUs = durationMs * 1000L
            val fps = captureFpsStr?.toFloatOrNull()?.toInt() ?: 30

            val filePath = if (uri.scheme == "file") uri.path else null

            VideoMetadata(
                uri = uri,
                filePath = filePath,
                width = w,
                height = h,
                durationUs = durationUs,
                rotation = rotation,
                estimatedFps = fps
            )
        } finally {
            retriever.release()
        }
    }

    suspend fun getFrameAtTime(
        uri: Uri,
        timeUs: Long,
        targetWidth: Int = 480,
        targetHeight: Int = 480
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            // Use OPTION_CLOSEST for accurate frame inspection
            val frame = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                targetWidth,
                targetHeight
            ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }
}
