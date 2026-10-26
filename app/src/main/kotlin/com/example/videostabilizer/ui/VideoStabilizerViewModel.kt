package com.example.videostabilizer.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videostabilizer.model.FrameStabilizationTransform
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.ObjectBoundary
import com.example.videostabilizer.model.OutOfFrameStrategy
import com.example.videostabilizer.model.RotationStabilizationMode
import com.example.videostabilizer.model.StabilizationConfig
import com.example.videostabilizer.model.StabilizationMode
import com.example.videostabilizer.model.TrackingFrameState
import com.example.videostabilizer.stabilizer.VideoStabilizationEngine
import com.example.videostabilizer.video.DemoVideoGenerator
import com.example.videostabilizer.video.VideoExporter
import com.example.videostabilizer.video.VideoFrameExtractor
import com.example.videostabilizer.video.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface AppScreenState {
    data object Empty : AppScreenState
    data class SelectingTarget(
        val videoUri: Uri,
        val metadata: VideoMetadata,
        val previewBitmap: Bitmap,
        val currentSelection: NormalizedRect?,
        val identifiedBoundary: ObjectBoundary? = null,
        val autoSnapBoundary: Boolean = true
    ) : AppScreenState
    data class Processing(
        val videoUri: Uri,
        val phase: String,
        val progress: Float
    ) : AppScreenState
    data class Ready(
        val videoUri: Uri,
        val metadata: VideoMetadata,
        val trackingStates: List<TrackingFrameState>,
        val transforms: List<FrameStabilizationTransform>,
        val config: StabilizationConfig,
        val currentFrameIndex: Int = 0,
        val isPlaying: Boolean = false,
        val viewMode: ViewMode = ViewMode.STABILIZED,
        val isExporting: Boolean = false,
        val exportProgress: Float = 0f,
        val exportedFile: File? = null
    ) : AppScreenState
    data class Error(val message: String) : AppScreenState
}

enum class ViewMode(val label: String) {
    STABILIZED("Stabilized"),
    SPLIT_SCREEN("Split Compare"),
    TRACKING_HUD("Tracking HUD")
}

class VideoStabilizerViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = VideoStabilizationEngine(application)
    private val frameExtractor = VideoFrameExtractor(application)
    private val demoGenerator = DemoVideoGenerator(application)
    private val exporter = VideoExporter(application)

    private val _uiState = MutableStateFlow<AppScreenState>(AppScreenState.Empty)
    val uiState: StateFlow<AppScreenState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private val frameCache = mutableMapOf<Int, Bitmap>()

    fun loadVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = AppScreenState.Processing(uri, "Loading video...", 0.1f)
                val metadata = frameExtractor.getMetadata(uri)
                val initialBitmap = frameExtractor.getFrameAtTime(uri, 0L, 720, 1280)
                    ?: throw IllegalStateException("Could not extract frame from video")

                val defaultSelection = NormalizedRect.centeredAt(0.5f, 0.5f, 0.22f, 0.22f)
                val initialBoundary = withContext(Dispatchers.Default) {
                    engine.identifyBoundary(initialBitmap, defaultSelection)
                }

                _uiState.value = AppScreenState.SelectingTarget(
                    videoUri = uri,
                    metadata = metadata,
                    previewBitmap = initialBitmap,
                    currentSelection = initialBoundary.tightBox,
                    identifiedBoundary = initialBoundary,
                    autoSnapBoundary = true
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = AppScreenState.Error("Failed to load video: ${e.message}")
            }
        }
    }

    fun generateAndLoadDemoVideo() {
        viewModelScope.launch {
            try {
                _uiState.value = AppScreenState.Processing(
                    Uri.EMPTY,
                    "Generating shaky test video with rotating drone...",
                    0.05f
                )
                val demoFile = demoGenerator.generateShakyDemoVideo { progress ->
                    _uiState.value = AppScreenState.Processing(
                        Uri.EMPTY,
                        "Rendering realistic rotating footage...",
                        progress
                    )
                }
                loadVideo(Uri.fromFile(demoFile))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = AppScreenState.Error("Demo generation failed: ${e.message}")
            }
        }
    }

    fun updateSelection(rect: NormalizedRect) {
        val current = _uiState.value
        if (current is AppScreenState.SelectingTarget) {
            viewModelScope.launch {
                val boundary = withContext(Dispatchers.Default) {
                    engine.identifyBoundary(current.previewBitmap, rect)
                }
                val effectiveRect = if (current.autoSnapBoundary) boundary.tightBox else rect

                _uiState.value = current.copy(
                    currentSelection = effectiveRect,
                    identifiedBoundary = boundary
                )
            }
        }
    }

    fun toggleAutoSnapBoundary() {
        val current = _uiState.value
        if (current is AppScreenState.SelectingTarget) {
            val nextSnap = !current.autoSnapBoundary
            val nextRect = if (nextSnap && current.identifiedBoundary != null) {
                current.identifiedBoundary.tightBox
            } else {
                current.currentSelection
            }
            _uiState.value = current.copy(
                autoSnapBoundary = nextSnap,
                currentSelection = nextRect
            )
        }
    }

    fun startStabilization(config: StabilizationConfig = StabilizationConfig()) {
        val current = _uiState.value
        if (current !is AppScreenState.SelectingTarget || current.currentSelection == null) return

        val uri = current.videoUri
        val selection = current.currentSelection

        viewModelScope.launch {
            try {
                _uiState.value = AppScreenState.Processing(uri, "Identifying boundary & starting AI tracker", 0.05f)

                val result = engine.analyzeAndStabilize(
                    videoUri = uri,
                    initialSelection = selection,
                    config = config,
                    onProgress = { phase, progress ->
                        _uiState.value = AppScreenState.Processing(uri, phase, progress)
                    }
                )

                frameCache.clear()

                _uiState.value = AppScreenState.Ready(
                    videoUri = uri,
                    metadata = result.metadata,
                    trackingStates = result.trackingStates,
                    transforms = result.transforms,
                    config = config,
                    currentFrameIndex = 0,
                    isPlaying = true
                )

                startPlayback()
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = AppScreenState.Error("Stabilization failed: ${e.message}")
            }
        }
    }

    fun updateConfig(newConfig: StabilizationConfig) {
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            val newTransforms = engine.recalculateWithConfig(current.trackingStates, newConfig)
            _uiState.value = current.copy(
                config = newConfig,
                transforms = newTransforms
            )
        }
    }

    fun setViewMode(mode: ViewMode) {
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            _uiState.value = current.copy(viewMode = mode)
        }
    }

    fun togglePlayback() {
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            if (current.isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }
    }

    fun seekToFrame(frameIndex: Int) {
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            val clamped = frameIndex.coerceIn(0, current.transforms.size - 1)
            _uiState.value = current.copy(currentFrameIndex = clamped)
        }
    }

    private fun startPlayback() {
        val current = _uiState.value
        if (current !is AppScreenState.Ready) return

        playbackJob?.cancel()
        _uiState.value = current.copy(isPlaying = true)

        playbackJob = viewModelScope.launch {
            val fps = current.metadata.estimatedFps.coerceIn(15, 60)
            val frameDelay = (1000L / fps)

            while (isActive) {
                val state = _uiState.value as? AppScreenState.Ready ?: break
                if (!state.isPlaying) break

                val nextFrame = (state.currentFrameIndex + 1) % state.transforms.size
                _uiState.update { prev ->
                    if (prev is AppScreenState.Ready) prev.copy(currentFrameIndex = nextFrame) else prev
                }
                delay(frameDelay)
            }
        }
    }

    private fun pausePlayback() {
        playbackJob?.cancel()
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            _uiState.value = current.copy(isPlaying = false)
        }
    }

    suspend fun getFrameBitmap(uri: Uri, frameIndex: Int, targetW: Int, targetH: Int): Bitmap? {
        val cached = frameCache[frameIndex]
        if (cached != null && !cached.isRecycled) return cached

        val current = _uiState.value as? AppScreenState.Ready
        val fps = current?.metadata?.estimatedFps ?: 30
        val timeUs = frameIndex * (1_000_000L / fps)

        val bmp = frameExtractor.getFrameAtTime(uri, timeUs, targetW, targetH)
        if (bmp != null) {
            if (frameCache.size > 120) {
                val firstKey = frameCache.keys.first()
                frameCache.remove(firstKey)?.recycle()
            }
            frameCache[frameIndex] = bmp
        }
        return bmp
    }

    fun exportVideo() {
        val current = _uiState.value
        if (current !is AppScreenState.Ready || current.isExporting) return

        viewModelScope.launch {
            _uiState.value = current.copy(isExporting = true, exportProgress = 0.01f)
            try {
                val exported = exporter.exportStabilizedVideo(
                    sourceUri = current.videoUri,
                    transforms = current.transforms,
                    onProgress = { progress ->
                        _uiState.update { prev ->
                            if (prev is AppScreenState.Ready) prev.copy(exportProgress = progress) else prev
                        }
                    }
                )
                _uiState.update { prev ->
                    if (prev is AppScreenState.Ready) prev.copy(
                        isExporting = false,
                        exportedFile = exported
                    ) else prev
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { prev ->
                    if (prev is AppScreenState.Ready) prev.copy(isExporting = false) else prev
                }
            }
        }
    }

    fun dismissExportDialog() {
        val current = _uiState.value
        if (current is AppScreenState.Ready) {
            _uiState.value = current.copy(exportedFile = null)
        }
    }

    fun resetToEmpty() {
        playbackJob?.cancel()
        frameCache.values.forEach { it.recycle() }
        frameCache.clear()
        _uiState.value = AppScreenState.Empty
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        frameCache.values.forEach { it.recycle() }
        frameCache.clear()
    }
}
