package com.remodex.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remodex.android.core.model.PlanState
import com.remodex.android.core.model.PlanStep
import com.remodex.android.core.model.PlanStepStatus

@Composable
fun PlanCard(
    planState: PlanState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Plan",
                style = MaterialTheme.typography.titleMedium,
            )
            planState.explanation?.let {
                if (it.isNotBlank()) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            planState.steps.forEach { step ->
                PlanStepRow(step)
            }
            planState.streamingText?.let {
                if (it.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StreamingIndicator()
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(step: PlanStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        val (icon, tint) = when (step.status) {
            PlanStepStatus.COMPLETED -> Icons.Rounded.Check to Color(0xFF2E7D32)
            PlanStepStatus.IN_PROGRESS -> Icons.Rounded.PlayArrow to MaterialTheme.colorScheme.primary
            PlanStepStatus.FAILED -> Icons.Rounded.Close to MaterialTheme.colorScheme.error
            PlanStepStatus.PENDING -> Icons.Rounded.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            step.step,
            style = MaterialTheme.typography.bodyMedium,
            color = when (step.status) {
                PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
        )
    }
}
