package com.remodex.android.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.remodex.android.core.attachment.ImageAttachmentPipeline
import com.remodex.android.core.model.ImageAttachment
import com.remodex.android.core.model.RelayConnectionState
import com.remodex.android.core.model.timelineLazyKey
import com.remodex.android.core.voice.VoicePreflightResult
import com.remodex.android.core.voice.VoiceRecordingManager
import com.remodex.android.ui.component.ApprovalCard
import com.remodex.android.ui.component.ComposerBar
import com.remodex.android.ui.component.DiffStatsBadge
import com.remodex.android.ui.component.GitActionsSheet
import com.remodex.android.ui.component.GitBranchSelector
import com.remodex.android.ui.component.GitDiffSheet
import com.remodex.android.ui.component.MessageBubble
import com.remodex.android.ui.component.PlanCard
import com.remodex.android.ui.component.RemodexBrandIcon
import com.remodex.android.ui.component.SidebarContent
import com.remodex.android.ui.component.StructuredInputCard
import com.remodex.android.ui.component.UsageStatusSheetContent
import com.remodex.android.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemodexApp(viewModel: RemodexAppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val voiceManager = remember(context) { VoiceRecordingManager(context.applicationContext) }
    var showOnboarding by rememberSaveable { mutableStateOf(!viewModel.hasCompletedOnboarding()) }
    var showScanner by rememberSaveable { mutableStateOf(!uiState.hasSavedPairing) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDiffSheet by rememberSaveable { mutableStateOf(false) }
    var showBranchSelector by rememberSaveable { mutableStateOf(false) }
    var showGitActions by rememberSaveable { mutableStateOf(false) }
    var showUsageStatusSheet by rememberSaveable { mutableStateOf(false) }
    var sidebarProjectOrder by rememberSaveable { mutableStateOf(viewModel.sidebarProjectOrder()) }

    // Lifecycle observer for foreground/background tracking
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onForegroundResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onBackgroundPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) showScanner = false
    }

    // Auto-fetch git status when selected thread changes
    val selectedCwd = uiState.selectedThreadId?.let { tid ->
        uiState.threads.find { it.id == tid }?.cwd
    }
    val selectedGitStatus = uiState.selectedThreadId?.let { uiState.gitStatusByThread[it] } ?: uiState.gitStatus
    val shouldShowErrorBanner = uiState.errorMessage != null &&
        uiState.connectionState != RelayConnectionState.CONNECTING &&
        uiState.connectionState != RelayConnectionState.HANDSHAKING
    LaunchedEffect(selectedCwd, uiState.isConnected) {
        if (uiState.isConnected && selectedCwd != null) {
            viewModel.gitStatus(selectedCwd)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            showOnboarding -> {
                OnboardingScreen(
                    onFinished = {
                        viewModel.setOnboardingCompleted()
                        showOnboarding = false
                    },
                )
            }

            showSettings -> {
                com.remodex.android.ui.screen.SettingsScreen(
                    uiState = uiState,
                    onBack = { showSettings = false },
                    onReconnect = viewModel::reconnect,
                    onForgetPairing = {
                        viewModel.disconnect(clearPairing = true)
                        showSettings = false
                    },
                    onAccessModeChanged = viewModel::setAccessMode,
                )
            }

            !uiState.hasSavedPairing || showScanner -> {
                    PairingScreen(
                        uiState = uiState,
                        pairingFeedback = viewModel.pairingFeedback,
                        onScanned = {
                            viewModel.onPairingCodeSubmitted(it)
                        },
                        onReconnect = viewModel::reconnect,
                    onDismissScanner = { showScanner = false },
                )
            }

            else -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(300.dp),
                            drawerContainerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            SidebarContent(
                                uiState = uiState,
                                projectOrder = sidebarProjectOrder,
                                onProjectOrderChanged = { nextOrder ->
                                    sidebarProjectOrder = nextOrder
                                    viewModel.saveSidebarProjectOrder(nextOrder)
                                },
                                onStartThreadInProject = { projectPath ->
                                    viewModel.startThread(projectPath)
                                    scope.launch { drawerState.close() }
                                },
                                onSelectThread = {
                                    viewModel.selectThread(it)
                                    scope.launch { drawerState.close() }
                                },
                                onShowSettings = {
                                    showSettings = true
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    },
                ) {
                    val threadId = uiState.selectedThreadId
                    val selectedThread = threadId?.let { tid -> uiState.threads.find { it.id == tid } }
                    val cwd = selectedThread?.cwd

                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                                title = {
                                    Column {
                                        Text(
                                            selectedThread?.title ?: "Dex",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (cwd != null) {
                                            Text(
                                                cwd,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Rounded.Menu, contentDescription = "Open drawer")
                                    }
                                },
                                actions = {
                                    // Diff stats badge
                                    if (cwd != null && selectedGitStatus != null) {
                                        DiffStatsBadge(
                                            gitStatus = selectedGitStatus,
                                            onClick = {
                                                viewModel.gitDiff(cwd)
                                                showDiffSheet = true
                                            },
                                        )
                                    }

                                    // Options menu
                                    IconButton(onClick = {
                                        if (cwd != null) {
                                            viewModel.gitStatus(cwd)
                                            showGitActions = true
                                        }
                                    }) {
                                        Icon(
                                            Icons.Rounded.MoreVert,
                                            contentDescription = "Options",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        },
                    ) { innerPadding ->
                        val messages = threadId?.let { uiState.messagesByThread[it].orEmpty() }.orEmpty()
                        val isRunning = threadId != null && threadId in uiState.runningThreadIds
                        val planState = threadId?.let { uiState.planStateByThread[it] }
                        val contextWindowUsage = threadId?.let(uiState.contextWindowUsageByThread::get)
                        val shouldAutoRefreshUsageStatus = threadId != null &&
                            uiState.isConnected &&
                            (contextWindowUsage == null || !uiState.hasResolvedRateLimitsSnapshot)
                        var draft by rememberSaveable(threadId) { mutableStateOf("") }
                        var composerAttachments by remember(threadId) { mutableStateOf(emptyList<ImageAttachment>()) }
                        var isVoiceRecording by remember(threadId) { mutableStateOf(false) }

                        LaunchedEffect(threadId, uiState.isConnected) {
                            if (threadId != null && shouldAutoRefreshUsageStatus) {
                                viewModel.refreshUsageStatus(threadId)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .imePadding(),
                        ) {
                            // Error banner
                            AnimatedVisibility(shouldShowErrorBanner) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            uiState.errorMessage ?: "",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = viewModel::reconnect) { Text("Reconnect") }
                                            OutlinedButton(onClick = { showScanner = true }) { Text("Scan QR") }
                                        }
                                    }
                                }
                            }

                            if (threadId == null) {
                                // Empty state
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        RemodexBrandIcon(
                                            modifier = Modifier
                                                .size(72.dp),
                                            contentDescription = "Remodex app icon",
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "Hi! How can I help?",
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Chats with your Mac will appear here.",
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        // Connection spinner
                                        if (!uiState.isConnected) {
                                            Spacer(Modifier.height(20.dp))
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                when (uiState.connectionState) {
                                                    RelayConnectionState.CONNECTING -> "Connecting..."
                                                    RelayConnectionState.HANDSHAKING -> "Securing connection..."
                                                    RelayConnectionState.RECONNECT_REQUIRED -> "Reconnecting..."
                                                    else -> "Offline"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Approval card
                                val approval = uiState.pendingApproval
                                if (approval != null && (approval.threadId == null || approval.threadId == threadId)) {
                                    ApprovalCard(
                                        approval = approval,
                                        onApprove = { viewModel.respondToApproval(true) },
                                        onReject = { viewModel.respondToApproval(false) },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }

                                // Structured input cards
                                uiState.structuredInputRequests
                                    .filter { it.threadId == threadId }
                                    .forEach { request ->
                                        StructuredInputCard(
                                            request = request,
                                            onSubmit = { answers ->
                                                viewModel.respondToStructuredInput(request.requestId, answers)
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }

                                // Plan card
                                planState?.let {
                                    PlanCard(
                                        planState = it,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }

                                val imagePickerLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.GetMultipleContents(),
                                ) { uris ->
                                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                                    scope.launch {
                                        val remainingSlots = (ImageAttachmentPipeline.maxComposerImages - composerAttachments.size)
                                            .coerceAtLeast(0)
                                        if (remainingSlots == 0) {
                                            return@launch
                                        }
                                        val nextAttachments = loadComposerAttachmentsFromUris(
                                            context = context,
                                            uris = uris.take(remainingSlots),
                                        )
                                        if (nextAttachments.isNotEmpty()) {
                                            composerAttachments = composerAttachments + nextAttachments
                                        } else {
                                            viewModel.setErrorMessage("Couldn't attach those images.")
                                        }
                                    }
                                }
                                val cameraPreviewLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.TakePicturePreview(),
                                ) { bitmap ->
                                    if (bitmap == null) return@rememberLauncherForActivityResult
                                    scope.launch {
                                        val attachment = buildComposerAttachmentFromBitmap(bitmap)
                                        if (attachment != null) {
                                            composerAttachments = composerAttachments + attachment
                                        } else {
                                            viewModel.setErrorMessage("Couldn't attach that photo.")
                                        }
                                    }
                                }
                                val voicePermissionLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.RequestPermission(),
                                ) { granted ->
                                    if (!granted) return@rememberLauncherForActivityResult
                                    keyboardController?.hide()
                                    isVoiceRecording = voiceManager.startRecording()
                                    if (!isVoiceRecording) {
                                        viewModel.setErrorMessage("Microphone unavailable right now.")
                                    }
                                }

                                DisposableEffect(threadId) {
                                    onDispose {
                                        if (isVoiceRecording) {
                                            voiceManager.cancelRecording()
                                        }
                                    }
                                }

                                // Message timeline
                                val listState = rememberLazyListState()
                                var lastAutoScrollMessageCount by remember(threadId) { mutableStateOf(0) }
                                LaunchedEffect(messages.size) {
                                    if (messages.isNotEmpty()) {
                                        val targetIndex = messages.size - 1
                                        if (lastAutoScrollMessageCount == 0 || messages.size - lastAutoScrollMessageCount > 6) {
                                            listState.scrollToItem(targetIndex)
                                        } else {
                                            listState.animateScrollToItem(targetIndex)
                                        }
                                        lastAutoScrollMessageCount = messages.size
                                    }
                                }

                                // Loading spinner when thread is selected but messages haven't arrived
                                if (messages.isEmpty() && threadId in uiState.loadingThreadIds && !isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            RemodexBrandIcon(
                                                modifier = Modifier.size(56.dp),
                                                contentDescription = "Remodex app icon",
                                            )
                                            Spacer(Modifier.height(18.dp))
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        itemsIndexed(
                                            items = messages,
                                            key = { index, message -> message.timelineLazyKey(index) },
                                        ) { _, message ->
                                            MessageBubble(message)
                                        }
                                    }
                                }

                                // Composer with integrated controls
                                ComposerBar(
                                    draft = draft,
                                    attachments = composerAttachments,
                                    onDraftChange = { draft = it },
                                    onSend = {
                                        if (draft.isNotBlank() || composerAttachments.isNotEmpty()) {
                                            viewModel.sendTurn(threadId, draft, composerAttachments)
                                            draft = ""
                                            composerAttachments = emptyList()
                                        }
                                    },
                                    onInterrupt = { viewModel.interruptTurn(threadId) },
                                    isRunning = isRunning,
                                    isConnected = uiState.isConnected,
                                    onPickImage = {
                                        keyboardController?.hide()
                                        imagePickerLauncher.launch("image/*")
                                    },
                                    onTakePhoto = {
                                        keyboardController?.hide()
                                        if (composerAttachments.size < ImageAttachmentPipeline.maxComposerImages) {
                                            cameraPreviewLauncher.launch(null)
                                        }
                                    },
                                    onRemoveAttachment = { attachmentId ->
                                        composerAttachments = composerAttachments.filterNot { it.id == attachmentId }
                                    },
                                    onVoice = {
                                        if (!viewModel.voiceTranscribing) {
                                            if (isVoiceRecording) {
                                                scope.launch {
                                                    val wavData = voiceManager.stopRecording()
                                                    isVoiceRecording = false
                                                    if (wavData == null) {
                                                        return@launch
                                                    }
                                                    when (val validation = voiceManager.validateRecording(wavData)) {
                                                        is VoicePreflightResult.Valid -> {
                                                            viewModel.transcribeVoice(wavData) { transcript ->
                                                                draft = appendVoiceTranscript(draft, transcript)
                                                            }
                                                        }
                                                        VoicePreflightResult.TooShort -> {
                                                            viewModel.setErrorMessage("Voice note was too short.")
                                                        }
                                                        is VoicePreflightResult.TooLong -> {
                                                            viewModel.setErrorMessage("Voice note is too long. Keep it under 60 seconds.")
                                                        }
                                                        is VoicePreflightResult.TooLarge -> {
                                                            viewModel.setErrorMessage("Voice note is too large to transcribe.")
                                                        }
                                                    }
                                                }
                                            } else {
                                                keyboardController?.hide()
                                                if (!voiceManager.hasPermission) {
                                                    voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                } else {
                                                    isVoiceRecording = voiceManager.startRecording()
                                                    if (!isVoiceRecording) {
                                                        viewModel.setErrorMessage("Microphone unavailable right now.")
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    isVoiceRecording = isVoiceRecording,
                                    isVoiceTranscribing = viewModel.voiceTranscribing,
                                    availableModels = uiState.availableModels,
                                    currentOverride = uiState.runtimeOverrideByThread[threadId],
                                    onOverrideChanged = { viewModel.setRuntimeOverride(threadId, it) },
                                    gitStatus = selectedGitStatus,
                                    onBranchClick = {
                                        if (cwd != null) {
                                            viewModel.gitBranches(cwd)
                                            showBranchSelector = true
                                        }
                                    },
                                    onGitMenuClick = {
                                        if (cwd != null) {
                                            viewModel.gitStatus(cwd)
                                            showGitActions = true
                                        }
                                    },
                                    selectedAccessMode = uiState.selectedAccessMode,
                                    onAccessModeSelected = viewModel::setAccessMode,
                                    onOpenCloud = {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://chatgpt.com/codex"),
                                            ),
                                        )
                                    },
                                    contextWindowUsage = contextWindowUsage,
                                    rateLimitBuckets = uiState.rateLimitBuckets,
                                    isLoadingRateLimits = uiState.isLoadingRateLimits,
                                    rateLimitsErrorMessage = uiState.rateLimitsErrorMessage,
                                    onShowUsageStatus = { showUsageStatusSheet = true },
                                )
                            }
                        }
                    }
                }

                // Git actions sheet
                if (showGitActions) {
                    val cwd = uiState.selectedThreadId?.let { tid ->
                        uiState.threads.find { it.id == tid }?.cwd
                    }
                    GitActionsSheet(
                        gitStatus = selectedGitStatus,
                        onUpdate = { cwd?.let { viewModel.gitStatus(it) } },
                        onCommit = { cwd?.let { viewModel.gitCommit(it) } },
                        onPush = { cwd?.let { viewModel.gitPush(it) } },
                        onCommitAndPush = {
                            cwd?.let {
                                viewModel.gitCommit(it)
                                viewModel.gitPush(it)
                            }
                        },
                        onDiff = {
                            cwd?.let {
                                viewModel.gitDiff(it)
                                showDiffSheet = true
                            }
                        },
                        onDiscard = { /* TODO: implement discard */ },
                        onBranch = {
                            cwd?.let {
                                viewModel.gitBranches(it)
                                showBranchSelector = true
                            }
                        },
                        onDismiss = { showGitActions = false },
                    )
                }

                // Diff sheet
                if (showDiffSheet) {
                    GitDiffSheet(
                        patch = viewModel.diffResult?.patch,
                        onDismiss = {
                            showDiffSheet = false
                            viewModel.clearDiffResult()
                        },
                    )
                }

                // Branch selector
                if (showBranchSelector) {
                    GitBranchSelector(
                        branches = uiState.gitBranches,
                        onSelect = { branch ->
                            val cwd = uiState.selectedThreadId?.let { tid ->
                                uiState.threads.find { it.id == tid }?.cwd
                            }
                            if (cwd != null) {
                                viewModel.gitCheckout(cwd, branch)
                            }
                            showBranchSelector = false
                        },
                        onDismiss = { showBranchSelector = false },
                    )
                }

                if (showUsageStatusSheet) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    LaunchedEffect(uiState.selectedThreadId, showUsageStatusSheet) {
                        val selectedThreadId = uiState.selectedThreadId
                        val selectedUsage = if (selectedThreadId != null) {
                            uiState.contextWindowUsageByThread[selectedThreadId]
                        } else {
                            null
                        }
                        if (showUsageStatusSheet &&
                            selectedThreadId != null &&
                            uiState.isConnected &&
                            (selectedUsage == null || !uiState.hasResolvedRateLimitsSnapshot)
                        ) {
                            viewModel.refreshUsageStatus(selectedThreadId)
                        }
                    }

                    ModalBottomSheet(
                        onDismissRequest = { showUsageStatusSheet = false },
                        sheetState = sheetState,
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        UsageStatusSheetContent(
                            usage = uiState.selectedThreadId?.let(uiState.contextWindowUsageByThread::get),
                            rateLimitBuckets = uiState.rateLimitBuckets,
                            isLoadingRateLimits = uiState.isLoadingRateLimits,
                            rateLimitsErrorMessage = uiState.rateLimitsErrorMessage,
                            onRefreshStatus = {
                                viewModel.refreshUsageStatus(uiState.selectedThreadId)
                            },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun loadComposerAttachmentsFromUris(
    context: Context,
    uris: List<Uri>,
): List<ImageAttachment> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri ->
        val sourceData = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@mapNotNull null
        ImageAttachmentPipeline.makeAttachment(sourceData)?.copy(sourceUri = uri.toString())
    }
}

private suspend fun buildComposerAttachmentFromBitmap(bitmap: Bitmap): ImageAttachment? = withContext(Dispatchers.Default) {
    ImageAttachmentPipeline.makeAttachment(bitmapToJpegBytes(bitmap) ?: return@withContext null)
}

private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray? {
    val stream = java.io.ByteArrayOutputStream()
    return if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
        stream.toByteArray()
    } else {
        null
    }
}

private fun appendVoiceTranscript(draft: String, transcript: String): String {
    val trimmedTranscript = transcript.trim()
    if (trimmedTranscript.isEmpty()) {
        return draft
    }
    return if (draft.isBlank()) {
        trimmedTranscript
    } else {
        draft.trimEnd() + "\n" + trimmedTranscript
    }
}

// ── Pairing Screen ────────────────────────────────────────────────────

@Composable
private fun PairingScreen(
    uiState: com.remodex.android.core.model.RemodexUiState,
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

        // App icon
        RemodexBrandIcon(
            modifier = Modifier
                .size(72.dp),
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

        // Connection status indicator
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

        // Error feedback
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

        // QR scanner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            // Scanner viewport
            AnimatedVisibility(scannerExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    QrScannerView(onDetected = onScanned)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
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

        Spacer(Modifier.height(24.dp))

//        // Manual code entry (collapsible)
//        Column(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(horizontal = 24.dp),
//        ) {
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clickable { showManualEntry = !showManualEntry }
//                    .padding(vertical = 8.dp),
//                verticalAlignment = Alignment.CenterVertically,
//            ) {
//                Text(
//                    "Paste pairing code",
//                    style = MaterialTheme.typography.titleSmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant,
//                )
//                Spacer(Modifier.weight(1f))
//                Text(
//                    if (showManualEntry) "Hide" else "Show",
//                    style = MaterialTheme.typography.labelMedium,
//                    color = MaterialTheme.colorScheme.primary,
//                )
//            }
//
//            AnimatedVisibility(showManualEntry) {
//                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
//                    OutlinedTextField(
//                        value = manualPairingCode,
//                        onValueChange = onManualPairingCodeChanged,
//                        modifier = Modifier.fillMaxWidth(),
//                        minLines = 3,
//                        maxLines = 6,
//                        placeholder = {
//                            Text(
//                                """{"v":2,"relay":"ws://..."}""",
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
//                            )
//                        },
//                        shape = RoundedCornerShape(12.dp),
//                    )
//                    Button(
//                        onClick = onSubmitManualCode,
//                        enabled = manualPairingCode.isNotBlank() && !isConnecting,
//                        modifier = Modifier.fillMaxWidth(),
//                        shape = RoundedCornerShape(12.dp),
//                    ) {
//                        if (isConnecting) {
//                            CircularProgressIndicator(
//                                modifier = Modifier.size(16.dp),
//                                strokeWidth = 2.dp,
//                                color = MaterialTheme.colorScheme.onPrimary,
//                            )
//                            Spacer(Modifier.width(8.dp))
//                        }
//                        Text("Connect")
//                    }
//                }
//            }
//        }

        Spacer(Modifier.height(40.dp))

        // Status label at bottom
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

// ── QR Scanner ────────────────────────────────────────────────────────

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
