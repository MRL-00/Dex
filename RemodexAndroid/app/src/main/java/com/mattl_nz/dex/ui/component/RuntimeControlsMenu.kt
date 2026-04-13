package com.mattl_nz.dex.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mattl_nz.dex.core.model.ModelOption
import com.mattl_nz.dex.core.model.ReasoningEffort
import com.mattl_nz.dex.core.model.ServiceTier
import com.mattl_nz.dex.core.model.ThreadRuntimeOverride

@Composable
fun RuntimeControlsBar(
    availableModels: List<ModelOption>,
    currentOverride: ThreadRuntimeOverride?,
    onOverrideChanged: (ThreadRuntimeOverride) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableModels.isEmpty()) return

    val override = currentOverride ?: ThreadRuntimeOverride()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Model selector
        var modelExpanded by remember { mutableStateOf(false) }
        val selectedModel = availableModels.find { it.id == override.model }
            ?: availableModels.find { it.isDefault }

        FilterChip(
            selected = override.model != null,
            onClick = { modelExpanded = true },
            label = {
                Text(
                    selectedModel?.displayName ?: "Model",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
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

        // Reasoning effort
        val currentModel = selectedModel ?: availableModels.firstOrNull()
        if (currentModel != null && currentModel.supportedReasoningEfforts.isNotEmpty()) {
            var effortExpanded by remember { mutableStateOf(false) }
            FilterChip(
                selected = override.reasoningEffort != null,
                onClick = { effortExpanded = true },
                label = {
                    Text(
                        override.reasoningEffort?.value ?: "Effort",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
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

        // Fast mode toggle
        FilterChip(
            selected = override.serviceTier == ServiceTier.FAST,
            onClick = {
                val newTier = if (override.serviceTier == ServiceTier.FAST) null else ServiceTier.FAST
                onOverrideChanged(override.copy(serviceTier = newTier))
            },
            label = { Text("Fast", style = MaterialTheme.typography.labelSmall) },
            leadingIcon = {
                Icon(Icons.Rounded.Speed, null, Modifier.size(14.dp))
            },
        )
    }
}
