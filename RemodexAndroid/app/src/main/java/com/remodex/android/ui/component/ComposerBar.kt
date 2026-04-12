package com.remodex.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.remodex.android.core.model.GitRepoSyncResult
import com.remodex.android.core.model.ModelOption
import com.remodex.android.core.model.ReasoningEffort
import com.remodex.android.core.model.ServiceTier
import com.remodex.android.core.model.ThreadRuntimeOverride

@Composable
fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    isRunning: Boolean,
    isConnected: Boolean,
    onAttach: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Runtime controls
    availableModels: List<ModelOption> = emptyList(),
    currentOverride: ThreadRuntimeOverride? = null,
    onOverrideChanged: ((ThreadRuntimeOverride) -> Unit)? = null,
    // Git info for bottom bar
    gitStatus: GitRepoSyncResult? = null,
    onBranchClick: (() -> Unit)? = null,
    onGitMenuClick: (() -> Unit)? = null,
    // Access mode
    accessModeLabel: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Composer row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Plus/attach button
            IconButton(
                onClick = onAttach ?: {},
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Text input
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Ask anything... @files, \$skills, /commands",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank() && !isRunning) onSend()
                }),
                maxLines = 6,
            )

            // Voice or Send/Stop
            if (onVoice != null && draft.isBlank() && !isRunning) {
                IconButton(
                    onClick = onVoice,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Voice",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isRunning) {
                IconButton(
                    onClick = onInterrupt,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                ) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank() && isConnected,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (draft.isNotBlank() && isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
                        tint = if (draft.isNotBlank() && isConnected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                }
            }
        }

        // Model / Effort row
        if (availableModels.isNotEmpty() && onOverrideChanged != null) {
            val override = currentOverride ?: ThreadRuntimeOverride()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Model selector chip
                var modelExpanded by remember { mutableStateOf(false) }
                val selectedModel = availableModels.find { it.id == override.model }
                    ?: availableModels.find { it.isDefault }

                Box {
                    ChipButton(
                        text = selectedModel?.displayName ?: "Model",
                        onClick = { modelExpanded = true },
                    )
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    onOverrideChanged(override.copy(model = model.id))
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }

                // Reasoning effort
                val currentModel = selectedModel ?: availableModels.firstOrNull()
                if (currentModel != null && currentModel.supportedReasoningEfforts.isNotEmpty()) {
                    var effortExpanded by remember { mutableStateOf(false) }
                    Box {
                        ChipButton(
                            text = override.reasoningEffort?.value ?: "Medium",
                            icon = { Icon(Icons.Rounded.Settings, null, Modifier.size(12.dp)) },
                            onClick = { effortExpanded = true },
                        )
                        DropdownMenu(
                            expanded = effortExpanded,
                            onDismissRequest = { effortExpanded = false },
                        ) {
                            currentModel.supportedReasoningEfforts.forEach { effort ->
                                DropdownMenuItem(
                                    text = { Text(effort) },
                                    onClick = {
                                        onOverrideChanged(override.copy(reasoningEffort = ReasoningEffort.fromString(effort)))
                                        effortExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom status bar: Local | Access mode | Branch | Settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "Local" chip
                ChipButton(
                    text = "Local",
                    icon = {
                        Icon(Icons.Rounded.Computer, null, Modifier.size(12.dp))
                    },
                    onClick = {},
                )

                // Access mode chip
                if (accessModeLabel != null) {
                    ChipButton(
                        text = "",
                        icon = {
                            Icon(Icons.Rounded.TaskAlt, null, Modifier.size(14.dp))
                        },
                        onClick = {},
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Branch selector
                if (gitStatus?.branch != null && onBranchClick != null) {
                    ChipButton(
                        text = gitStatus.branch,
                        icon = {
                            Icon(Icons.Rounded.CallMerge, null, Modifier.size(12.dp))
                        },
                        onClick = onBranchClick,
                    )
                }

                // Git menu button (settings gear)
                if (onGitMenuClick != null) {
                    IconButton(
                        onClick = onGitMenuClick,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Git actions",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            icon()
        }
        if (text.isNotBlank()) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
