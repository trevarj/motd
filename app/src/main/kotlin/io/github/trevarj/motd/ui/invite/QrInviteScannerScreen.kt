package io.github.trevarj.motd.ui.invite

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.R
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.decodeQrFrame
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

private const val SCANNER_LOG_TAG = "MotdInviteScanner"

private enum class CameraScanStatus { STARTING, SCANNING, DETECTED }

private fun scannerLog(message: String) {
    if (BuildConfig.DEBUG) Log.d(SCANNER_LOG_TAG, message)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrInviteScannerScreen(
    onBack: () -> Unit,
    onInvite: (String) -> Unit,
    cameraAvailable: Boolean? = null,
) {
    val context = LocalContext.current
    val hasCamera = cameraAvailable ?: remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var requested by remember { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            requested = true
            if (!it) showPaste = true
            scannerLog("camera permission granted=$it")
        }
    var paste by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(R.string.invite_scan_invalid)
    val cameraError = stringResource(R.string.invite_scan_camera_error)

    LaunchedEffect(error) {
        if (error == invalidMessage) {
            delay(2_000)
            error = null
        }
    }

    fun accept(raw: String): Boolean =
        runCatching { JoinInviteCodec.parseScanned(raw) }
            .fold(
                onSuccess = {
                    scannerLog("invite QR validated")
                    error = null
                    onInvite(JoinInviteCodec.encode(it))
                    true
                },
                onFailure = {
                    scannerLog("decoded QR rejected: ${it::class.java.simpleName}")
                    error = invalidMessage
                    false
                },
            )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                granted && hasCamera -> {
                    CameraScanner(
                        onValue = ::accept,
                        onError = {
                            error = cameraError
                            showPaste = true
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("invite_camera_preview"),
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (hasCamera) R.string.invite_scan_permission_help else R.string.invite_scan_no_camera,
                            ),
                        )
                        if (hasCamera) {
                            Button(
                                onClick = {
                                    requested = true
                                    permission.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.testTag("invite_camera_permission"),
                            ) { Text(stringResource(R.string.invite_scan_allow_camera)) }
                            if (requested && !granted) {
                                Text(stringResource(R.string.invite_scan_permission_denied), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pasteVisible = showPaste || !hasCamera
                if (hasCamera) {
                    TextButton(
                        onClick = { showPaste = !showPaste },
                        modifier = Modifier.testTag("invite_paste_toggle"),
                    ) {
                        Text(stringResource(if (pasteVisible) R.string.invite_scan_hide_paste else R.string.invite_scan_paste_title))
                    }
                }
                if (pasteVisible) {
                    OutlinedTextField(
                        value = paste,
                        onValueChange = { paste = it },
                        label = { Text(stringResource(R.string.invite_scan_paste_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("invite_paste"),
                        minLines = 2,
                    )
                    Button(
                        onClick = { accept(paste) },
                        enabled = paste.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("invite_paste_submit"),
                    ) { Text(stringResource(R.string.invite_scan_continue)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("invite_scan_error")) }
            }
        }
    }
}

@Composable
private fun CameraScanner(
    onValue: (String) -> Boolean,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    val analyzedFrames = remember { AtomicInteger() }
    var status by remember { mutableStateOf(CameraScanStatus.STARTING) }
    val previewView = remember(context) { PreviewView(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.size(250.dp)) {
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color.White,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(28.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("invite_scan_status"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    when (status) {
                        CameraScanStatus.STARTING -> R.string.invite_scan_starting
                        CameraScanStatus.SCANNING -> R.string.invite_scan_scanning
                        CameraScanStatus.DETECTED -> R.string.invite_scan_detected
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val activeCamera = camera
        if (activeCamera?.cameraInfo?.hasFlashUnit() == true) {
            IconButton(
                onClick = {
                    torch = !torch
                    activeCamera.cameraControl.enableTorch(torch)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).testTag("invite_torch"),
            ) {
                Icon(
                    if (torch) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                    contentDescription = stringResource(if (torch) R.string.invite_scan_torch_off else R.string.invite_scan_torch_on),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }

    DisposableEffect(executor) {
        onDispose { executor.shutdownNow() }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                runCatching {
                    val cameraProvider = future.get()
                    if (disposed) return@addListener
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (consumed.get()) return@setAnalyzer
                            val frameNumber = analyzedFrames.incrementAndGet()
                            if (frameNumber == 1) {
                                scannerLog(
                                    "frames active: ${image.width}x${image.height}, rotation=${image.imageInfo.rotationDegrees}",
                                )
                                mainExecutor.execute { status = CameraScanStatus.SCANNING }
                            } else if (frameNumber % 120 == 0) {
                                scannerLog("still scanning; frames=$frameNumber")
                            }
                            val plane = image.planes.firstOrNull() ?: return@setAnalyzer
                            val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
                            val crop = image.cropRect
                            val value =
                                decodeQrFrame(
                                    bytes,
                                    image.width,
                                    image.height,
                                    plane.rowStride,
                                    image.imageInfo.rotationDegrees,
                                    pixelStride = plane.pixelStride,
                                    cropLeft = crop.left,
                                    cropTop = crop.top,
                                    cropWidth = crop.width(),
                                    cropHeight = crop.height(),
                                )
                            if (value != null && consumed.compareAndSet(false, true)) {
                                scannerLog("QR decoded; validating")
                                mainExecutor.execute {
                                    status = CameraScanStatus.DETECTED
                                    if (!onValue(value)) {
                                        status = CameraScanStatus.SCANNING
                                        consumed.set(false)
                                    }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    boundProvider = cameraProvider
                    scannerLog("camera bound; waiting for frames")
                }.onFailure {
                    scannerLog("camera start failed: ${it::class.java.simpleName}")
                    onError()
                }
            },
            mainExecutor,
        )
        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            camera = null
        }
    }
}
