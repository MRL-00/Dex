package com.remodex.android.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.android.core.attachment.ImageAttachmentPipeline
import com.remodex.android.core.model.ComposerSlashCommand
import com.remodex.android.core.model.ConversationDiffSummaryCalculator
import com.remodex.android.core.model.ImageAttachment
import com.remodex.android.core.model.RelayConnectionState
import com.remodex.android.core.model.RemodexSkillMetadata
import com.remodex.android.core.model.RemodexTurnSkillMention
import com.remodex.android.core.model.SkillAutocompleteLogic
import com.remodex.android.core.model.SlashCommandLogic
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
    var showRepoDiffSheet by rememberSaveable { mutableStateOf(false) }
    var showThreadDiffSheet by rememberSaveable { mutableStateOf(false) }
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
    val selectedThread = uiState.selectedThreadId?.let { tid ->
        uiState.threads.find { it.id == tid }
    }
    val selectedCwd = selectedThread?.cwd
    val selectedGitStatus = resolveSelectedGitStatus(
        selectedThread = selectedThread,
        threads = uiState.threads,
        gitStatusByThread = uiState.gitStatusByThread,
        fallbackGitStatus = uiState.gitStatus,
    )
    val selectedThreadMessages = selectedThread?.id?.let { uiState.messagesByThread[it].orEmpty() }.orEmpty()
    val selectedThreadDiffTotals = ConversationDiffSummaryCalculator.totals(selectedThreadMessages)
    val selectedThreadDiffChunks = ConversationDiffSummaryCalculator.chunks(selectedThreadMessages)
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
                                    if (selectedThreadDiffTotals != null) {
                                        DiffStatsBadge(
                                            additions = selectedThreadDiffTotals.additions,
                                            deletions = selectedThreadDiffTotals.deletions,
                                            onClick = {
                                                showThreadDiffSheet = true
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
                        var composerMentionedSkills by remember(threadId) { mutableStateOf(emptyList<RemodexSkillMetadata>()) }
                        var skillAutocompleteItems by remember(threadId, cwd) { mutableStateOf(emptyList<RemodexSkillMetadata>()) }
                        var skillAutocompleteQuery by remember(threadId, cwd) { mutableStateOf("") }
                        var isSkillAutocompleteVisible by remember(threadId, cwd) { mutableStateOf(false) }
                        var isSkillAutocompleteLoading by remember(threadId, cwd) { mutableStateOf(false) }
                        var slashAutocompleteQuery by remember(threadId) { mutableStateOf("") }
                        var isSlashAutocompleteVisible by remember(threadId) { mutableStateOf(false) }
                        var isVoiceRecording by remember(threadId) { mutableStateOf(false) }

                        LaunchedEffect(threadId, uiState.isConnected) {
                            if (threadId != null && shouldAutoRefreshUsageStatus) {
                                viewModel.refreshUsageStatus(threadId)
                            }
                        }

                        LaunchedEffect(draft, cwd, uiState.isConnected) {
                            composerMentionedSkills = SkillAutocompleteLogic.filterMentionedSkills(
                                text = draft,
                                mentions = composerMentionedSkills,
                            )

                            val slashToken = if (uiState.isConnected) {
                                SlashCommandLogic.trailingSlashCommandToken(draft)
                            } else {
                                null
                            }
                            if (slashToken != null) {
                                slashAutocompleteQuery = slashToken.query
                                isSlashAutocompleteVisible = true
                                skillAutocompleteItems = emptyList()
                                skillAutocompleteQuery = ""
                                isSkillAutocompleteVisible = false
                                isSkillAutocompleteLoading = false
                                return@LaunchedEffect
                            }

                            slashAutocompleteQuery = ""
                            isSlashAutocompleteVisible = false

                            val token = if (uiState.isConnected) {
                                SkillAutocompleteLogic.trailingSkillAutocompleteToken(draft)
                            } else {
                                null
                            }
                            val query = token?.query?.trim().orEmpty()

                            if (cwd.isNullOrBlank() || token == null || query.length < 2) {
                                skillAutocompleteItems = emptyList()
                                skillAutocompleteQuery = query
                                isSkillAutocompleteVisible = false
                                isSkillAutocompleteLoading = false
                                return@LaunchedEffect
                            }

                            skillAutocompleteQuery = query
                            isSkillAutocompleteVisible = true

                            viewModel.cachedSkills(cwd)?.let { cachedSkills ->
                                skillAutocompleteItems = filterSkillAutocompleteItems(query, cachedSkills)
                                isSkillAutocompleteLoading = false
                            } ?: run {
                                skillAutocompleteItems = emptyList()
                                isSkillAutocompleteLoading = true
                            }

                            val expectedQuery = query
                            viewModel.loadSkills(cwd) { loadedSkills ->
                                val currentToken = SkillAutocompleteLogic.trailingSkillAutocompleteToken(draft)
                                val currentQuery = currentToken?.query?.trim().orEmpty()
                                if (!currentQuery.equals(expectedQuery, ignoreCase = false)) {
                                    return@loadSkills
                                }
                                skillAutocompleteItems = filterSkillAutocompleteItems(expectedQuery, loadedSkills)
                                isSkillAutocompleteLoading = false
                                isSkillAutocompleteVisible = true
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
                                            "Build something great!",
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
                                    mentionedSkills = composerMentionedSkills,
                                    skillAutocompleteItems = skillAutocompleteItems,
                                    skillAutocompleteQuery = skillAutocompleteQuery,
                                    isSkillAutocompleteVisible = isSkillAutocompleteVisible,
                                    isSkillAutocompleteLoading = isSkillAutocompleteLoading,
                                    slashAutocompleteQuery = slashAutocompleteQuery,
                                    isSlashAutocompleteVisible = isSlashAutocompleteVisible,
                                    onDraftChange = { updatedDraft ->
                                        draft = updatedDraft
                                        composerMentionedSkills = SkillAutocompleteLogic.filterMentionedSkills(
                                            text = updatedDraft,
                                            mentions = composerMentionedSkills,
                                        )
                                    },
                                    onSelectSkillAutocomplete = { skill ->
                                        SkillAutocompleteLogic.replacingTrailingSkillAutocompleteToken(
                                            text = draft,
                                            selectedSkill = skill.name,
                                        )?.let { updatedDraft ->
                                            draft = updatedDraft
                                        }
                                        if (composerMentionedSkills.none { it.id == skill.id }) {
                                            composerMentionedSkills = composerMentionedSkills + skill
                                        }
                                        skillAutocompleteItems = emptyList()
                                        skillAutocompleteQuery = ""
                                        isSkillAutocompleteVisible = false
                                        isSkillAutocompleteLoading = false
                                        slashAutocompleteQuery = ""
                                        isSlashAutocompleteVisible = false
                                    },
                                    onSelectSlashCommand = { command ->
                                        when (command) {
                                            ComposerSlashCommand.STATUS -> {
                                                draft = SlashCommandLogic.removingTrailingSlashCommandToken(draft) ?: draft
                                                showUsageStatusSheet = true
                                            }
                                            ComposerSlashCommand.SUBAGENTS -> {
                                                draft = SlashCommandLogic.replacingTrailingSlashCommandToken(
                                                    text = draft,
                                                    replacement = command.cannedPrompt.orEmpty(),
                                                ) ?: draft
                                            }
                                            ComposerSlashCommand.CODE_REVIEW,
                                            ComposerSlashCommand.FORK,
                                            -> Unit
                                        }
                                        slashAutocompleteQuery = ""
                                        isSlashAutocompleteVisible = false
                                        skillAutocompleteItems = emptyList()
                                        skillAutocompleteQuery = ""
                                        isSkillAutocompleteVisible = false
                                        isSkillAutocompleteLoading = false
                                    },
                                    onRemoveMentionedSkill = { skillId ->
                                        composerMentionedSkills.firstOrNull { it.id == skillId }?.let { mention ->
                                            draft = SkillAutocompleteLogic.removingSkillMention(
                                                text = draft,
                                                skillName = mention.name,
                                            )
                                        }
                                        composerMentionedSkills = composerMentionedSkills.filterNot { it.id == skillId }
                                    },
                                    onSend = {
                                        val activeSkillMentions = SkillAutocompleteLogic
                                            .filterMentionedSkills(draft, composerMentionedSkills)
                                            .map { skill ->
                                                RemodexTurnSkillMention(
                                                    id = skill.name.trim(),
                                                    name = skill.name.trim(),
                                                    path = skill.path?.trim()?.takeIf(String::isNotEmpty),
                                                )
                                            }
                                        if (draft.isNotBlank() || composerAttachments.isNotEmpty() || activeSkillMentions.isNotEmpty()) {
                                            viewModel.sendTurn(threadId, draft, composerAttachments, activeSkillMentions)
                                            draft = ""
                                            composerAttachments = emptyList()
                                            composerMentionedSkills = emptyList()
                                            skillAutocompleteItems = emptyList()
                                            skillAutocompleteQuery = ""
                                            isSkillAutocompleteVisible = false
                                            isSkillAutocompleteLoading = false
                                            slashAutocompleteQuery = ""
                                            isSlashAutocompleteVisible = false
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
                                showRepoDiffSheet = true
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
                if (showRepoDiffSheet) {
                    GitDiffSheet(
                        title = "Repository Changes",
                        patch = viewModel.diffResult?.patch,
                        onDismiss = {
                            showRepoDiffSheet = false
                            viewModel.clearDiffResult()
                        },
                    )
                }

                if (showThreadDiffSheet) {
                    GitDiffSheet(
                        title = "Conversation Changes",
                        chunks = selectedThreadDiffChunks,
                        emptyLabel = "No conversation changes",
                        onDismiss = {
                            showThreadDiffSheet = false
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

private fun filterSkillAutocompleteItems(
    query: String,
    skills: List<RemodexSkillMetadata>,
): List<RemodexSkillMetadata> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) {
        return emptyList()
    }
    return skills.asSequence()
        .filter { it.searchBlob.contains(needle) }
        .take(6)
        .toList()
}

private fun resolveSelectedGitStatus(
    selectedThread: com.remodex.android.core.model.ThreadSummary?,
    threads: List<com.remodex.android.core.model.ThreadSummary>,
    gitStatusByThread: Map<String, com.remodex.android.core.model.GitRepoSyncResult>,
    fallbackGitStatus: com.remodex.android.core.model.GitRepoSyncResult?,
): com.remodex.android.core.model.GitRepoSyncResult? {
    val selectedThreadId = selectedThread?.id
    if (selectedThreadId != null) {
        gitStatusByThread[selectedThreadId]?.let { return it }
    }

    val selectedCwd = selectedThread?.cwd?.trimEnd('/') ?: return fallbackGitStatus
    val siblingThreadIds = threads
        .asSequence()
        .filter { thread -> thread.cwd?.trimEnd('/') == selectedCwd }
        .map { thread -> thread.id }
        .toSet()

    return gitStatusByThread.entries
        .firstOrNull { (threadId, _) -> threadId in siblingThreadIds }
        ?.value
        ?: fallbackGitStatus
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
