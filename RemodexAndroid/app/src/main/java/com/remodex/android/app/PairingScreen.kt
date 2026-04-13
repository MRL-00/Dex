package com.remodex.android.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.remodex.android.core.model.RelayConnectionState
import com.remodex.android.core.model.RemodexUiState
import com.remodex.android.ui.component.RemodexBrandIcon

@Composable
internal fun PairingScreen(
    uiState: RemodexUiState,
    pairingFeedback: String?,
    onScanned: (String) -> Unit,
    onReconnect: () -> Unit,
    onDismissScanner: () -> Unit,
) {
    var scannerExpanded by rememberSaveable { mutableStateOf(true) }
    val isConnecting = uiState.connectionState == RelayConnectionState.CONNECTING ||
        uiState.connectionState == RelayConnectionState.HANDSHAKING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))

        RemodexBrandIcon(
            modifier = Modifier.size(72.dp),
            contentDescription = "Remodex app icon",
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "Dex",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Pair with your Mac to get started",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isConnecting) {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    if (uiState.connectionState == RelayConnectionState.HANDSHAKING) "Securing..." else "Connecting...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (!pairingFeedback.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = pairingFeedback,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            AnimatedVisibility(scannerExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .border(0.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp)),
                ) {
                    QrScannerView(onDetected = onScanned)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { scannerExpanded = !scannerExpanded },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (scannerExpanded) "Hide Scanner" else "Scan QR")
                }

                if (uiState.hasSavedPairing) {
                    Button(
                        onClick = {
                            scannerExpanded = false
                            onDismissScanner()
                            onReconnect()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isConnecting,
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Reconnect")
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        if (uiState.errorMessage != null &&
            uiState.connectionState != RelayConnectionState.CONNECTING &&
            uiState.connectionState != RelayConnectionState.HANDSHAKING) {
            Text(
                uiState.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                uiState.secureStatusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun QrScannerView(onDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val latestOnDetected by rememberUpdatedState(onDetected)
    var permissionGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Camera permission is required to scan the pairing QR code.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }
        }
        return
    }

    val scanner = remember { BarcodeScanning.getClient() }
    var handled by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(permissionGranted, previewView, handled) {
        if (!permissionGranted) {
            onDispose { }
        } else if (handled) {
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            onDispose { }
        } else {
            val executor = ContextCompat.getMainExecutor(context)
            var disposed = false
            val bindCamera = Runnable {
                if (disposed) return@Runnable
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || handled) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (!handled && raw != null) {
                                handled = true
                                runCatching { cameraProvider.unbindAll() }
                                latestOnDetected(raw)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
            cameraProviderFuture.addListener(bindCamera, executor)
            onDispose {
                disposed = true
                if (cameraProviderFuture.isDone) {
                    runCatching { cameraProviderFuture.get().unbindAll() }
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        factory = { previewView },
    )

    DisposableEffect(Unit) {
        onDispose {
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            runCatching { scanner.close() }
        }
    }
}
