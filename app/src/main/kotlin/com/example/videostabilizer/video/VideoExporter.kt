package com.example.videostabilizer.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import com.example.videostabilizer.model.FrameStabilizationTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoExporter(
    private val context: Context,
    private val frameExtractor: VideoFrameExtractor = VideoFrameExtractor(context)
) {

    suspend fun exportStabilizedVideo(
        sourceUri: Uri,
        transforms: List<FrameStabilizationTransform>,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val metadata = frameExtractor.getMetadata(sourceUri)
        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val outputFile = File(exportDir, "stabilized_${System.currentTimeMillis()}.mp4")

        val outWidth = (metadata.width / 16) * 16
        val outHeight = (metadata.height / 16) * 16
        val fps = metadata.estimatedFps.coerceIn(15, 60)
        val bitRate = (outWidth * outHeight * fps * 0.15).toInt().coerceIn(2_000_000, 12_000_000)

        val format = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val totalFrames = transforms.size.coerceAtLeast(1)
        val frameDurationUs = 1_000_000L / fps
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        try {
            for (i in transforms.indices) {
                val transform = transforms[i]
                val timestampUs = i * frameDurationUs

                val frameBitmap = frameExtractor.getFrameAtTime(sourceUri, timestampUs, outWidth, outHeight)
                if (frameBitmap != null) {
                    val canvas = inputSurface.lockHardwareCanvas()
                    canvas.drawColor(Color.BLACK)

                    val cx = transform.cropCenter.x * outWidth
                    val cy = transform.cropCenter.y * outHeight
                    val zoom = transform.zoom

                    val matrix = Matrix()
                    matrix.postTranslate(-cx, -cy)
                    if (transform.cameraRotationDegrees != 0f) {
                        matrix.postRotate(transform.cameraRotationDegrees)
                    }
                    matrix.postScale(zoom, zoom)
                    matrix.postTranslate(outWidth / 2f, outHeight / 2f)

                    canvas.drawBitmap(frameBitmap, matrix, paint)

                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(encoder, bufferInfo, muxer, false) { track ->
                    videoTrackIndex = track
                    muxerStarted = true
                }

                onProgress((i + 1).toFloat() / totalFrames)
            }

            encoder.signalEndOfInputStream()
            drainEncoder(encoder, bufferInfo, muxer, true) { track ->
                videoTrackIndex = track
                muxerStarted = true
            }

        } finally {
            try {
                encoder.stop()
                encoder.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        outputFile
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        endOfStream: Boolean,
        onTrackAdded: (Int) -> Unit
    ) {
        val timeoutUs = if (endOfStream) 10000L else 0L
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val track = muxer.addTrack(newFormat)
                muxer.start()
                onTrackAdded(track)
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(0, encodedData, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }
}
