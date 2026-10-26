package com.example.videostabilizer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.videostabilizer.model.FrameStabilizationTransform
import com.example.videostabilizer.model.NormalizedRect
import com.example.videostabilizer.model.OutOfFrameStrategy
import com.example.videostabilizer.model.RotationStabilizationMode
import com.example.videostabilizer.model.StabilizationConfig
import com.example.videostabilizer.model.StabilizationMode
import com.example.videostabilizer.model.TrackingFrameState
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoStabilizerScreen(
    viewModel: VideoStabilizerViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadVideo(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AI Video Stabilizer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Rotation & Centering",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF14171E),
                    titleContentColor = Color.White
                ),
                actions = {
                    when (state) {
                        is AppScreenState.Ready -> {
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Text("⚙️", fontSize = 18.sp)
                            }
                            IconButton(onClick = { viewModel.exportVideo() }) {
                                Text("📤", fontSize = 18.sp)
                            }
                        }
                        else -> {
                            TextButton(onClick = { viewModel.generateAndLoadDemoVideo() }) {
                                Text("Demo Video", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            )
        },
        containerColor = Color(0xFF0F1117)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is AppScreenState.Empty -> {
                    EmptyStateView(
                        onPickVideo = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onLoadDemo = { viewModel.generateAndLoadDemoVideo() }
                    )
                }

                is AppScreenState.SelectingTarget -> {
                    SelectingTargetView(
                        state = currentState,
                        onSelectionChanged = { viewModel.updateSelection(it) },
                        onToggleAutoSnap = { viewModel.toggleAutoSnapBoundary() },
                        onStabilize = { viewModel.startStabilization() },
                        onCancel = { viewModel.resetToEmpty() }
                    )
                }

                is AppScreenState.Processing -> {
                    ProcessingStateView(
                        phase = currentState.phase,
                        progress = currentState.progress
                    )
                }

                is AppScreenState.Ready -> {
                    ReadyStabilizedView(
                        state = currentState,
                        viewModel = viewModel,
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }

                is AppScreenState.Error -> {
                    ErrorStateView(
                        message = currentState.message,
                        onRetry = { viewModel.resetToEmpty() }
                    )
                }
            }

            // Export progress dialog
            val readyState = state as? AppScreenState.Ready
            if (readyState?.isExporting == true) {
                ExportingDialog(progress = readyState.exportProgress)
            }

            // Export complete dialog
            if (readyState?.exportedFile != null) {
                ExportSuccessDialog(
                    file = readyState.exportedFile,
                    onDismiss = { viewModel.dismissExportDialog() },
                    onShare = {
                        val fileUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            readyState.exportedFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, fileUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Stabilized Video"))
                    }
                )
            }

            // Settings Bottom Sheet
            if (showSettingsSheet && readyState != null) {
                StabilizationSettingsBottomSheet(
                    config = readyState.config,
                    onConfigChanged = { viewModel.updateConfig(it) },
                    onDismiss = { showSettingsSheet = false }
                )
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    onPickVideo: () -> Unit,
    onLoadDemo: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .background(Color(0xFF1E222D), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🎯", fontSize = 42.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "AI Object Centering & Stabilization",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Select any object in your video. The AI detects the actual object boundary, tracks its position and rotation even when occluded or out of frame, and keeps it centered and steady.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9EABB8),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onPickVideo,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Choose Video from Gallery", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedButton(
            onClick = onLoadDemo,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(50.dp)
        ) {
            Text("Try Demo Video (Rotating Target)", fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D26))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Features:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                FeatureItem("🔍 Automatic object boundary detection & contour snap")
                FeatureItem("🔄 Real-time rotation tracking & horizon lock options")
                FeatureItem("⚡ Kalman kinematic predictive coasting when out of frame")
                FeatureItem("🎬 Deadzone & Gaussian trajectory smoothing")
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Color(0xFF8A94A6),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun SelectingTargetView(
    state: AppScreenState.SelectingTarget,
    onSelectionChanged: (NormalizedRect) -> Unit,
    onToggleAutoSnap: () -> Unit,
    onStabilize: () -> Unit,
    onCancel: () -> Unit
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var currentDrag by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Boundary Identification Status Badge
        Surface(
            color = Color(0xFF1E2330),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔍", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.identifiedBoundary != null) "Object Boundary Identified" else "Select Target Subject",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    val statusSubtitle = if (state.identifiedBoundary != null) {
                        "Orientation: ${"%.1f".format(state.identifiedBoundary.rotationDegrees)}° | Snapped to actual object boundary"
                    } else {
                        "Tap or drag over any subject to segment actual boundaries"
                    }
                    Text(
                        statusSubtitle,
                        color = Color(0xFF8C98A8),
                        fontSize = 11.sp
                    )
                }

                // Auto-snap toggle chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (state.autoSnapBoundary) Color(0xFF00E5FF).copy(alpha = 0.18f) else Color.DarkGray)
                        .clickable { onToggleAutoSnap() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (state.autoSnapBoundary) "Auto-Snap: ON" else "Raw Box",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.autoSnapBoundary) Color(0xFF00E5FF) else Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Selection Canvas
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val aspect = state.metadata.width.toFloat() / state.metadata.height.toFloat()
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight

            val displayW: Float
            val displayH: Float
            if (canvasWidth.value / canvasHeight.value > aspect) {
                displayH = canvasHeight.value
                displayW = displayH * aspect
            } else {
                displayW = canvasWidth.value
                displayH = displayW / aspect
            }

            val bmp = state.previewBitmap

            Canvas(
                modifier = Modifier
                    .size(displayW.dp, displayH.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height).coerceIn(0f, 1f)
                            val boxSize = 0.20f
                            onSelectionChanged(
                                NormalizedRect.centeredAt(normX, normY, boxSize, boxSize)
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                currentDrag = offset
                            },
                            onDrag = { change, _ ->
                                currentDrag = change.position
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = currentDrag
                                if (start != null && end != null) {
                                    val left = min(start.x, end.x) / size.width
                                    val right = max(start.x, end.x) / size.width
                                    val top = min(start.y, end.y) / size.height
                                    val bottom = max(start.y, end.y) / size.height

                                    val w = (right - left).coerceAtLeast(0.04f)
                                    val h = (bottom - top).coerceAtLeast(0.04f)
                                    onSelectionChanged(
                                        NormalizedRect(
                                            left = left.coerceIn(0f, 1f),
                                            top = top.coerceIn(0f, 1f),
                                            right = (left + w).coerceIn(0f, 1f),
                                            bottom = (top + h).coerceIn(0f, 1f)
                                        )
                                    )
                                }
                                dragStart = null
                                currentDrag = null
                            }
                        )
                    }
            ) {
                // 1. Draw frame bitmap
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )

                // 2. Draw active dragging rect
                val s = dragStart
                val e = currentDrag
                if (s != null && e != null) {
                    val l = min(s.x, e.x)
                    val t = min(s.y, e.y)
                    val w = abs(e.x - s.x)
                    val h = abs(e.y - s.y)
                    drawRect(
                        color = Color(0x6600E5FF),
                        topLeft = Offset(l, t),
                        size = Size(w, h)
                    )
                    drawRect(
                        color = Color(0xFF00E5FF),
                        topLeft = Offset(l, t),
                        size = Size(w, h),
                        style = Stroke(width = 3f)
                    )
                } else {
                    // 3. Draw identified actual boundary contour polygon
                    val bnd = state.identifiedBoundary
                    if (bnd != null && bnd.contourPoints.size >= 3) {
                        val polyPath = Path().apply {
                            val p0 = bnd.contourPoints.first()
                            moveTo(p0.x * size.width, p0.y * size.height)
                            for (idx in 1 until bnd.contourPoints.size) {
                                val p = bnd.contourPoints[idx]
                                lineTo(p.x * size.width, p.y * size.height)
                            }
                            close()
                        }
                        // Semi-transparent polygon fill
                        drawPath(polyPath, color = Color(0x4400E5FF))
                        // Glowing perimeter line
                        drawPath(polyPath, color = Color(0xFF00E5FF), style = Stroke(width = 3.5f))

                        // Draw orientation axis ray
                        val cx = bnd.centroidX * size.width
                        val cy = bnd.centroidY * size.height
                        val rad = Math.toRadians(bnd.rotationDegrees.toDouble())
                        val rayLen = min(bnd.tightBox.width * size.width, bnd.tightBox.height * size.height) * 0.7f
                        val rayEndX = cx + cos(rad).toFloat() * rayLen
                        val rayEndY = cy + sin(rad).toFloat() * rayLen

                        drawLine(
                            color = Color.Yellow,
                            start = Offset(cx, cy),
                            end = Offset(rayEndX, rayEndY),
                            strokeWidth = 3.5f
                        )
                        drawCircle(Color.Yellow, radius = 5f, center = Offset(rayEndX, rayEndY))
                    }

                    // 4. Draw bounding box reticle
                    val sel = state.currentSelection
                    if (sel != null) {
                        val pxL = sel.left * size.width
                        val pxT = sel.top * size.height
                        val pxW = sel.width * size.width
                        val pxH = sel.height * size.height

                        val bracketLen = min(pxW, pxH) * 0.25f
                        val strokeW = 4f
                        val bracketColor = Color(0xFF00E5FF)

                        // Top-left
                        drawLine(bracketColor, Offset(pxL, pxT), Offset(pxL + bracketLen, pxT), strokeW)
                        drawLine(bracketColor, Offset(pxL, pxT), Offset(pxL, pxT + bracketLen), strokeW)
                        // Top-right
                        drawLine(bracketColor, Offset(pxL + pxW, pxT), Offset(pxL + pxW - bracketLen, pxT), strokeW)
                        drawLine(bracketColor, Offset(pxL + pxW, pxT), Offset(pxL + pxW, pxT + bracketLen), strokeW)
                        // Bottom-left
                        drawLine(bracketColor, Offset(pxL, pxT + pxH), Offset(pxL + bracketLen, pxT + pxH), strokeW)
                        drawLine(bracketColor, Offset(pxL, pxT + pxH), Offset(pxL, pxT + pxH - bracketLen), strokeW)
                        // Bottom-right
                        drawLine(bracketColor, Offset(pxL + pxW, pxT + pxH), Offset(pxL + pxW - bracketLen, pxT + pxH), strokeW)
                        drawLine(bracketColor, Offset(pxL + pxW, pxT + pxH), Offset(pxL + pxW, pxT + pxH - bracketLen), strokeW)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = onStabilize,
                enabled = state.currentSelection != null,
                modifier = Modifier
                    .weight(2f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Stabilize & Track Object", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProcessingStateView(
    phase: String,
    progress: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            strokeWidth = 6.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color(0xFF222836)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = phase,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(progress * 100).roundToInt()}%",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color(0xFF1E222D)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Tracking object boundary contour, rotation angles, and computing smooth camera stabilization...",
            fontSize = 12.sp,
            color = Color(0xFF8A94A6),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun ReadyStabilizedView(
    state: AppScreenState.Ready,
    viewModel: VideoStabilizerViewModel,
    onOpenSettings: () -> Unit
) {
    val currentTransform = state.transforms.getOrNull(state.currentFrameIndex)
    val currentTracking = state.trackingStates.getOrNull(state.currentFrameIndex)

    val frameBitmap by produceState<Bitmap?>(initialValue = null, state.currentFrameIndex) {
        value = viewModel.getFrameBitmap(
            state.videoUri,
            state.currentFrameIndex,
            state.metadata.width,
            state.metadata.height
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Mode Selector Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ViewMode.values().forEach { mode ->
                FilterChip(
                    selected = state.viewMode == mode,
                    onClick = { viewModel.setViewMode(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Viewport Video Canvas
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val aspect = state.metadata.width.toFloat() / state.metadata.height.toFloat()
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight

            val displayW: Float
            val displayH: Float
            if (canvasWidth.value / canvasHeight.value > aspect) {
                displayH = canvasHeight.value
                displayW = displayH * aspect
            } else {
                displayW = canvasWidth.value
                displayH = displayW / aspect
            }

            val bmp = frameBitmap
            if (bmp != null && currentTransform != null) {
                Canvas(modifier = Modifier.size(displayW.dp, displayH.dp)) {
                    val canvasW = size.width
                    val canvasH = size.height

                    when (state.viewMode) {
                        ViewMode.STABILIZED -> {
                            val nativeCanvas = drawContext.canvas.nativeCanvas
                            nativeCanvas.save()

                            val cx = currentTransform.cropCenter.x * canvasW
                            val cy = currentTransform.cropCenter.y * canvasH
                            val zoom = currentTransform.zoom

                            val matrix = android.graphics.Matrix().apply {
                                postTranslate(-cx, -cy)
                                if (currentTransform.cameraRotationDegrees != 0f) {
                                    postRotate(currentTransform.cameraRotationDegrees)
                                }
                                postScale(zoom, zoom)
                                postTranslate(canvasW / 2f, canvasH / 2f)
                            }
                            nativeCanvas.concat(matrix)

                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstSize = IntSize(canvasW.toInt(), canvasH.toInt())
                            )
                            nativeCanvas.restore()

                            // Stabilized center reticle
                            drawCircle(
                                color = Color(0x3300E5FF),
                                radius = 24f,
                                center = Offset(canvasW / 2f, canvasH / 2f),
                                style = Stroke(width = 2f)
                            )
                        }

                        ViewMode.SPLIT_SCREEN -> {
                            val nativeCanvas = drawContext.canvas.nativeCanvas

                            // Left half: Original Shaky
                            drawContext.canvas.save()
                            drawContext.canvas.clipRect(0f, 0f, canvasW / 2f, canvasH)
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstSize = IntSize(canvasW.toInt(), canvasH.toInt())
                            )
                            drawContext.canvas.restore()

                            // Right half: Stabilized & rotation corrected
                            nativeCanvas.save()
                            drawContext.canvas.clipRect(canvasW / 2f, 0f, canvasW, canvasH)

                            val cx = currentTransform.cropCenter.x * canvasW
                            val cy = currentTransform.cropCenter.y * canvasH
                            val zoom = currentTransform.zoom

                            val matrix = android.graphics.Matrix().apply {
                                postTranslate(-cx, -cy)
                                if (currentTransform.cameraRotationDegrees != 0f) {
                                    postRotate(currentTransform.cameraRotationDegrees)
                                }
                                postScale(zoom, zoom)
                                postTranslate(canvasW / 2f, canvasH / 2f)
                            }
                            nativeCanvas.concat(matrix)
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstSize = IntSize(canvasW.toInt(), canvasH.toInt())
                            )
                            nativeCanvas.restore()

                            // Split divider line
                            drawLine(
                                color = Color.White,
                                start = Offset(canvasW / 2f, 0f),
                                end = Offset(canvasW / 2f, canvasH),
                                strokeWidth = 3f
                            )
                        }

                        ViewMode.TRACKING_HUD -> {
                            // Raw video with boundary and rotation overlays
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstSize = IntSize(canvasW.toInt(), canvasH.toInt())
                            )

                            // 1. Draw Crop Window
                            val cropW = canvasW / currentTransform.zoom
                            val cropH = canvasH / currentTransform.zoom
                            val cropL = currentTransform.cropCenter.x * canvasW - cropW / 2f
                            val cropT = currentTransform.cropCenter.y * canvasH - cropH / 2f
                            drawRect(
                                color = Color(0x88FFEB3B),
                                topLeft = Offset(cropL, cropT),
                                size = Size(cropW, cropH),
                                style = Stroke(width = 2f)
                            )

                            // 2. Draw actual identified boundary contour polygon
                            val contour = currentTransform.boundaryContour
                            if (contour.size >= 3) {
                                val path = Path().apply {
                                    val p0 = contour.first()
                                    moveTo(p0.x * canvasW, p0.y * canvasH)
                                    for (ci in 1 until contour.size) {
                                        val p = contour[ci]
                                        lineTo(p.x * canvasW, p.y * canvasH)
                                    }
                                    close()
                                }
                                val contourColor = if (currentTransform.isOutOfFrame) Color(0x66FF3D00) else Color(0x6600E5FF)
                                val strokeColor = if (currentTransform.isOutOfFrame) Color(0xFFFF3D00) else Color(0xFF00E5FF)
                                drawPath(path, color = contourColor)
                                drawPath(path, color = strokeColor, style = Stroke(width = 3f))
                            }

                            // 3. Draw tracked target box & rotation ray
                            if (currentTracking != null) {
                                val box = currentTracking.targetBox
                                val bx = box.left * canvasW
                                val by = box.top * canvasH
                                val bw = box.width * canvasW
                                val bh = box.height * canvasH

                                val boxColor = if (currentTracking.isOutOfFrame) Color(0xFFFF3D00) else Color(0xFF00E676)
                                drawRect(
                                    color = boxColor,
                                    topLeft = Offset(bx, by),
                                    size = Size(bw, bh),
                                    style = Stroke(width = 2f)
                                )

                                // Rotation orientation vector ray
                                val cx = bx + bw / 2f
                                val cy = by + bh / 2f
                                val rad = Math.toRadians(currentTracking.rotationDegrees.toDouble())
                                val arrowLen = min(bw, bh) * 0.75f
                                val arrowEndX = cx + cos(rad).toFloat() * arrowLen
                                val arrowEndY = cy + sin(rad).toFloat() * arrowLen

                                drawLine(
                                    color = Color.Yellow,
                                    start = Offset(cx, cy),
                                    end = Offset(arrowEndX, arrowEndY),
                                    strokeWidth = 3.5f
                                )
                                drawCircle(Color.Yellow, radius = 5f, center = Offset(arrowEndX, arrowEndY))
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            // HUD Status Overlay Banner
            if (currentTracking != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    val angleText = "Rot: ${"%.1f".format(currentTracking.rotationDegrees)}°"
                    val statusText = if (currentTracking.isOutOfFrame) {
                        "⚠️ SUBJECT OUT OF FRAME ($angleText — Inertial Coasting)"
                    } else {
                        "🎯 TRACKED ($angleText | ${(currentTracking.confidence * 100).roundToInt()}% Conf)"
                    }
                    val textColor = if (currentTracking.isOutOfFrame) Color(0xFFFF5252) else Color(0xFF69F0AE)
                    Text(text = statusText, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Playback Scrubber & Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val maxFrames = (state.transforms.size - 1).coerceAtLeast(1)
                    Slider(
                        value = state.currentFrameIndex.toFloat(),
                        onValueChange = { viewModel.seekToFrame(it.toInt()) },
                        valueRange = 0f..maxFrames.toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Frame ${state.currentFrameIndex + 1} / ${state.transforms.size}",
                        fontSize = 12.sp,
                        color = Color(0xFF9EABB8)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.seekToFrame(state.currentFrameIndex - 1) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("◀", color = Color.White, fontSize = 14.sp)
                        }

                        IconButton(
                            onClick = { viewModel.togglePlayback() },
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Text(
                                text = if (state.isPlaying) "⏸" else "▶",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { viewModel.seekToFrame(state.currentFrameIndex + 1) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("▶", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    TextButton(onClick = { viewModel.resetToEmpty() }) {
                        Text("New Video", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StabilizationSettingsBottomSheet(
    config: StabilizationConfig,
    onConfigChanged: (StabilizationConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1B1E28)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Stabilization Parameters",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Mode Selection
            Text("Stabilization Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            StabilizationMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (config.mode == mode) Color(0xFF262D3D) else Color.Transparent)
                        .clickable { onConfigChanged(config.copy(mode = mode)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(mode.description, color = Color(0xFF8E9BAE), fontSize = 11.sp)
                    }
                    if (config.mode == mode) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Rotation Stabilization Mode Selection
            Text("Object Rotation Handling", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            RotationStabilizationMode.values().forEach { rotMode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (config.rotationMode == rotMode) Color(0xFF262D3D) else Color.Transparent)
                        .clickable { onConfigChanged(config.copy(rotationMode = rotMode)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rotMode.title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                        Text(rotMode.description, color = Color(0xFF8E9BAE), fontSize = 11.sp)
                    }
                    if (config.rotationMode == rotMode) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Zoom Factor Slider
            Text(
                "Zoom / Crop Factor: ${"%.2f".format(config.zoomFactor)}x",
                fontSize = 13.sp,
                color = Color.White
            )
            Text(
                "Provides margin buffer to reposition the object at center without black borders.",
                fontSize = 11.sp,
                color = Color(0xFF8E9BAE)
            )
            Slider(
                value = config.zoomFactor,
                onValueChange = { onConfigChanged(config.copy(zoomFactor = it)) },
                valueRange = 1.10f..2.20f,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Out of frame strategy
            Text("Out-of-Frame Subject Behavior", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            OutOfFrameStrategy.values().forEach { strat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (config.outOfFrameStrategy == strat) Color(0xFF262D3D) else Color.Transparent)
                        .clickable { onConfigChanged(config.copy(outOfFrameStrategy = strat)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strat.title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (config.outOfFrameStrategy == strat) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ExportingDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        containerColor = Color(0xFF1E222D),
        title = { Text("Exporting Stabilized MP4", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("${(progress * 100).roundToInt()}% rendered", color = Color(0xFF9EABB8), fontSize = 13.sp)
            }
        }
    )
}

@Composable
private fun ExportSuccessDialog(
    file: File,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E222D),
        title = { Text("Export Complete!", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Your AI stabilized video is saved:", color = Color(0xFF9EABB8), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(file.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Size: ${(file.length() / 1024 / 1024 * 10) / 10.0} MB", color = Color(0xFF8E9BAE), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Share Video")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color.White)
            }
        }
    )
}

@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⚠️", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Video Processing Error",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color(0xFF9EABB8),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}
