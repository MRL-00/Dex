package com.remodex.android.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.android.core.attachment.ImageAttachmentPipeline
import com.remodex.android.core.model.AccessMode
import com.remodex.android.core.model.ContextWindowUsage
import com.remodex.android.core.model.GitRepoSyncResult
import com.remodex.android.core.model.ImageAttachment
import com.remodex.android.core.model.ModelOption
import com.remodex.android.core.model.RateLimitBucket
import com.remodex.android.core.model.ReasoningEffort
import com.remodex.android.core.model.ThreadRuntimeOverride
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun ComposerBar(
    draft: String,
    attachments: List<ImageAttachment> = emptyList(),
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    isRunning: Boolean,
    isConnected: Boolean,
    onPickImage: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onRemoveAttachment: ((String) -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    isVoiceRecording: Boolean = false,
    isVoiceTranscribing: Boolean = false,
    modifier: Modifier = Modifier,
    availableModels: List<ModelOption> = emptyList(),
    currentOverride: ThreadRuntimeOverride? = null,
    onOverrideChanged: ((ThreadRuntimeOverride) -> Unit)? = null,
    gitStatus: GitRepoSyncResult? = null,
    onBranchClick: (() -> Unit)? = null,
    onGitMenuClick: (() -> Unit)? = null,
    selectedAccessMode: AccessMode = AccessMode.ON_REQUEST,
    onAccessModeSelected: ((AccessMode) -> Unit)? = null,
    onOpenCloud: (() -> Unit)? = null,
    contextWindowUsage: ContextWindowUsage? = null,
    rateLimitBuckets: List<RateLimitBucket> = emptyList(),
    isLoadingRateLimits: Boolean = false,
    rateLimitsErrorMessage: String? = null,
    onShowUsageStatus: (() -> Unit)? = null,
) {
    val composerShape = RoundedCornerShape(28.dp)
    val override = currentOverride ?: ThreadRuntimeOverride()
    val selectedModel = availableModels.find { it.id == override.model } ?: availableModels.find { it.isDefault }
    val selectedReasoning = override.reasoningEffort?.value ?: "Medium"
    val showVoiceButton = onVoice != null
    val sendEnabled = (draft.isNotBlank() || attachments.isNotEmpty()) &&
        isConnected &&
        !isRunning &&
        !isVoiceRecording &&
        !isVoiceTranscribing
    var attachmentsExpanded by remember { mutableStateOf(false) }
    var runtimeExpanded by remember { mutableStateOf(false) }
    var accessExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = composerShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 3.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (attachments.isNotEmpty()) {
                    ComposerAttachmentPreview(
                        attachments = attachments,
                        onRemove = onRemoveAttachment,
                    )
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp),
                    placeholder = {
                        Text(
                            "Ask anything... @files, \$skills, /commands",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (sendEnabled) {
                                onSend()
                            }
                        },
                    ),
                    maxLines = 6,
                )

                if (isVoiceRecording || isVoiceTranscribing) {
                    VoiceStatusChip(
                        label = if (isVoiceRecording) {
                            "Recording voice note..."
                        } else {
                            "Transcribing voice note..."
                        },
                        isRecording = isVoiceRecording,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        InlineIconButton(
                            onClick = { attachmentsExpanded = true },
                            enabled = onPickImage != null || onTakePhoto != null,
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "Attach",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = attachmentsExpanded,
                            onDismissRequest = { attachmentsExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo library") },
                                onClick = {
                                    attachmentsExpanded = false
                                    onPickImage?.invoke()
                                },
                                enabled = onPickImage != null && attachments.size < ImageAttachmentPipeline.maxComposerImages,
                            )
                            DropdownMenuItem(
                                text = { Text("Take a photo") },
                                onClick = {
                                    attachmentsExpanded = false
                                    onTakePhoto?.invoke()
                                },
                                enabled = onTakePhoto != null && attachments.size < ImageAttachmentPipeline.maxComposerImages,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (availableModels.isNotEmpty() && onOverrideChanged != null) {
                            ModelMenuChip(
                                label = selectedModel?.displayName ?: "Model",
                                availableModels = availableModels,
                                selectedModelId = selectedModel?.id,
                                onSelect = { modelId ->
                                    onOverrideChanged(override.copy(model = modelId))
                                },
                            )

                            val reasoningOptions = selectedModel?.supportedReasoningEfforts.orEmpty()
                            if (reasoningOptions.isNotEmpty()) {
                                ReasoningMenuChip(
                                    label = selectedReasoning,
                                    options = reasoningOptions,
                                    onSelect = { effort ->
                                        onOverrideChanged(
                                            override.copy(
                                                reasoningEffort = ReasoningEffort.fromString(effort),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (showVoiceButton) {
                        if (isVoiceTranscribing) {
                            CircularActionButton(
                                onClick = {},
                                enabled = false,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (isVoiceRecording) {
                            CircularActionButton(
                                onClick = onVoice,
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription = "Stop voice recording",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onError,
                                )
                            }
                        } else {
                            InlineIconButton(
                                onClick = onVoice,
                                enabled = isConnected && !isRunning,
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = "Voice",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (isRunning) {
                        CircularActionButton(
                            onClick = onInterrupt,
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.surface,
                            )
                        }
                    } else {
                        CircularActionButton(
                            onClick = onSend,
                            enabled = sendEnabled,
                            containerColor = if (sendEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                            },
                            contentColor = if (sendEnabled) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        ) {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = "Send",
                                modifier = Modifier.size(18.dp),
                                tint = if (sendEnabled) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ChipButton(
                        text = "Local",
                        icon = { Icon(Icons.Rounded.Computer, null, Modifier.size(12.dp)) },
                        onClick = { runtimeExpanded = true },
                        showChevron = true,
                    )
                    DropdownMenu(
                        expanded = runtimeExpanded,
                        onDismissRequest = { runtimeExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cloud") },
                            onClick = {
                                runtimeExpanded = false
                                onOpenCloud?.invoke()
                            },
                            enabled = onOpenCloud != null,
                        )
                        DropdownMenuItem(
                            text = { Text("New worktree") },
                            onClick = { runtimeExpanded = false },
                            enabled = false,
                        )
                        DropdownMenuItem(
                            text = { Text("Local") },
                            onClick = { runtimeExpanded = false },
                            trailingIcon = { Text("✓") },
                            enabled = false,
                        )
                    }
                }

                if (onAccessModeSelected != null) {
                    val isFullAccess = selectedAccessMode == AccessMode.FULL_ACCESS
                    Box {
                        ChipButton(
                            text = "",
                            icon = {
                                Icon(
                                    if (isFullAccess) Icons.Rounded.Settings else Icons.Rounded.TaskAlt,
                                    null,
                                    Modifier.size(14.dp),
                                )
                            },
                            onClick = { accessExpanded = true },
                            showChevron = true,
                        )
                        DropdownMenu(
                            expanded = accessExpanded,
                            onDismissRequest = { accessExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ask first") },
                                onClick = {
                                    accessExpanded = false
                                    onAccessModeSelected(AccessMode.ON_REQUEST)
                                },
                                trailingIcon = if (!isFullAccess) {
                                    { Text("✓") }
                                } else {
                                    null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Full access") },
                                onClick = {
                                    accessExpanded = false
                                    onAccessModeSelected(AccessMode.FULL_ACCESS)
                                },
                                trailingIcon = if (isFullAccess) {
                                    { Text("✓") }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (gitStatus?.branch != null && onBranchClick != null) {
                    ChipButton(
                        text = gitStatus.branch,
                        icon = { Icon(Icons.AutoMirrored.Rounded.CallMerge, null, Modifier.size(12.dp)) },
                        onClick = onBranchClick,
                        showChevron = true,
                        modifier = Modifier.widthIn(max = 132.dp),
                    )
                }

                if (onGitMenuClick != null) {
                    InlineIconButton(onClick = onGitMenuClick) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Git actions",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (onShowUsageStatus != null) {
                    StatusRingButton(
                        usage = contextWindowUsage,
                        onClick = onShowUsageStatus,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentPreview(
    attachments: List<ImageAttachment>,
    onRemove: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Box {
                val bitmap = remember(attachment.thumbnailBase64) {
                    ImageAttachmentPipeline.decodeThumbnailBitmap(attachment.thumbnailBase64)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Composer attachment",
                        modifier = Modifier
                            .size(70.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
                if (onRemove != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .clickable { onRemove(attachment.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove attachment",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceStatusChip(
    label: String,
    isRecording: Boolean,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isRecording) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
        } else {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isRecording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ModelMenuChip(
    label: String,
    availableModels: List<ModelOption>,
    selectedModelId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ChipButton(
            text = label,
            onClick = { expanded = true },
            showChevron = true,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            model.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                    trailingIcon = if (selectedModelId == model.id) {
                        { Text("✓") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ReasoningMenuChip(
    label: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ChipButton(
            text = label,
            icon = { Icon(Icons.Rounded.Settings, null, Modifier.size(12.dp)) },
            onClick = { expanded = true },
            showChevron = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun InlineIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(28.dp),
    ) {
        content()
    }
}

@Composable
private fun CircularActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
            content = content,
        )
    }
}

@Composable
private fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            icon()
        }
        if (text.isNotBlank()) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showChevron) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
