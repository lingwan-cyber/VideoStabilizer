package com.example.videostabilizer.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a realistic synthetic test video containing:
 * - High camera shake / jitter (simulating hand-held running/walking).
 * - A colorful tracked target (sports ball / drone / runner).
 * - Scenarios where the target moves fast and goes partially or fully out of frame, then re-enters.
 * Encodes directly into an MP4 file using MediaCodec and MediaMuxer.
 */
class DemoVideoGenerator(private val context: Context) {

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FRAME_RATE = 30
        const val DURATION_SECONDS = 6
        const val TOTAL_FRAMES = FRAME_RATE * DURATION_SECONDS
        const val BIT_RATE = 4_000_000
        const val MIME_TYPE = "video/avc" // H.264
    }

    suspend fun generateShakyDemoVideo(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "demo_shaky_video.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }

        val frameDurationUs = 1_000_000L / FRAME_RATE

        try {
            for (frame in 0 until TOTAL_FRAMES) {
                val t = frame.toFloat() / FRAME_RATE
                val presentationTimeUs = frame * frameDurationUs

                // Lock canvas from inputSurface
                val canvas: Canvas = inputSurface.lockHardwareCanvas()

                // 1. Handheld camera shake simulation (multi-frequency perlin-like shake)
                val shakeX = (sin(t * 18.0) * 45.0 + sin(t * 37.0) * 20.0 + cos(t * 9.0) * 35.0).toFloat()
                val shakeY = (cos(t * 15.0) * 40.0 + sin(t * 29.0) * 18.0 + sin(t * 7.0) * 30.0).toFloat()

                canvas.save()
                canvas.translate(shakeX, shakeY)

                // 2. Draw background environment (sky, mountains, buildings, grid)
                drawSceneBackground(canvas, paint, t)

                // 3. Draw moving target:
                // Target moves across screen; in seconds 2.5 to 3.5, it dips out of frame to the right/top, then returns!
                val targetTrajectory = computeDemoTargetPosition(t)
                val targetX = targetTrajectory.first
                val targetY = targetTrajectory.second
                val isOut = targetX < -40 || targetX > WIDTH + 40 || targetY < -40 || targetY > HEIGHT + 40

                drawTrackedSubject(canvas, paint, targetX, targetY, t)

                canvas.restore()

                // Draw status HUD on raw video
                paint.color = Color.argb(160, 0, 0, 0)
                canvas.drawRect(Rect(0, 0, WIDTH, 120), paint)
                canvas.drawText("SHAKY SOURCE TEST FOOTAGE", 30f, 50f, textPaint)
                val targetStatus = if (isOut) "SUBJECT: OUT OF FRAME (Re-entering soon)" else "SUBJECT: IN FRAME"
                textPaint.color = if (isOut) Color.RED else Color.GREEN
                canvas.drawText(targetStatus, 30f, 90f, textPaint)
                textPaint.color = Color.WHITE

                inputSurface.unlockCanvasAndPost(canvas)

                // Drain encoder output
                drainEncoder(encoder, bufferInfo, muxer, false) { index ->
                    trackIndex = index
                    muxerStarted = true
                }

                onProgress(frame.toFloat() / TOTAL_FRAMES)
            }

            // Signal end of stream
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, bufferInfo, muxer, true) { index ->
                trackIndex = index
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
                if (muxerStarted) {
                    muxer.stop()
                }
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

    private fun drawSceneBackground(canvas: Canvas, paint: Paint, t: Float) {
        // Sky gradient
        paint.color = Color.rgb(35, 45, 65)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Horizon & Ground
        paint.color = Color.rgb(24, 28, 36)
        canvas.drawRect(0f, HEIGHT * 0.55f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // City buildings / skyline
        paint.color = Color.rgb(48, 56, 75)
        val bldgWidth = 80f
        for (i in -2..12) {
            val bx = i * bldgWidth
            val bh = 180f + (sin(i.toDouble() * 3.7) * 90f).toFloat()
            canvas.drawRect(bx, HEIGHT * 0.55f - bh, bx + bldgWidth - 6, HEIGHT * 0.55f, paint)

            // Building windows
            paint.color = Color.rgb(180, 200, 120)
            for (wy in 0..4) {
                for (wx in 0..2) {
                    if ((i + wy + wx) % 3 != 0) {
                        canvas.drawRect(
                            bx + 10 + wx * 20,
                            HEIGHT * 0.55f - bh + 20 + wy * 30,
                            bx + 22 + wx * 20,
                            HEIGHT * 0.55f - bh + 32 + wy * 30,
                            paint
                        )
                    }
                }
            }
            paint.color = Color.rgb(48, 56, 75)
        }

        // Perspective grid lines on ground
        paint.color = Color.argb(60, 255, 255, 255)
        paint.strokeWidth = 2f
        for (g in 0..8) {
            val gx = g * (WIDTH / 8f)
            canvas.drawLine(WIDTH * 0.5f, HEIGHT * 0.55f, gx, HEIGHT.toFloat(), paint)
        }
        for (gy in 1..6) {
            val y = HEIGHT * 0.55f + (gy * gy) * 12f
            canvas.drawLine(0f, y, WIDTH.toFloat(), y, paint)
        }
    }

    private fun computeDemoTargetPosition(t: Float): Pair<Float, Float> {
        // Complex motion: figure-8 and dips outside the frame between t=2.6s and t=3.6s
        val baseCenterX = WIDTH * 0.5f
        val baseCenterY = HEIGHT * 0.52f

        var x = baseCenterX + (sin(t * 1.8) * 260.0).toFloat()
        var y = baseCenterY + (cos(t * 3.2) * 220.0).toFloat()

        // Excursion out of frame around t = 2.4s to 3.6s
        if (t in 2.3f..3.7f) {
            val bump = sin((t - 2.3) / 1.4 * Math.PI).toFloat()
            x += bump * 380f // Pushes target beyond right edge (> WIDTH + 50)
            y -= bump * 150f
        }

        return Pair(x, y)
    }

    private fun drawTrackedSubject(canvas: Canvas, paint: Paint, x: Float, y: Float, t: Float) {
        val radius = 46f
        // Object rotates at approx 45 degrees per second plus speed bursts
        val rotationDeg = t * 65f

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotationDeg)

        // Quadcopter Drone / Rotating Craft Body
        // 1. Cross arms
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10f
        paint.color = Color.rgb(60, 68, 85)
        canvas.drawLine(-radius * 1.2f, -radius * 1.2f, radius * 1.2f, radius * 1.2f, paint)
        canvas.drawLine(-radius * 1.2f, radius * 1.2f, radius * 1.2f, -radius * 1.2f, paint)

        // 2. Rotors at four corners
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 229, 255) // Cyan rotors
        val armDist = radius * 1.15f
        canvas.drawCircle(-armDist, -armDist, 16f, paint)
        canvas.drawCircle(armDist, -armDist, 16f, paint)
        canvas.drawCircle(-armDist, armDist, 16f, paint)
        canvas.drawCircle(armDist, armDist, 16f, paint)

        // 3. Central aerodynamic fuselage
        paint.color = Color.rgb(255, 61, 0) // Vibrant Coral Orange
        canvas.drawCircle(0f, 0f, radius * 0.7f, paint)

        // 4. Directional heading nose cone (shows forward orientation distinctly)
        paint.color = Color.rgb(255, 235, 59) // Yellow nose
        val path = android.graphics.Path().apply {
            moveTo(0f, -radius * 1.1f)
            lineTo(18f, -radius * 0.4f)
            lineTo(-18f, -radius * 0.4f)
            close()
        }
        canvas.drawPath(path, paint)

        // 5. Center eye / optical sensor
        paint.color = Color.WHITE
        canvas.drawCircle(0f, 0f, radius * 0.3f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(0f, 0f, radius * 0.15f, paint)

        canvas.restore()
    }
}
