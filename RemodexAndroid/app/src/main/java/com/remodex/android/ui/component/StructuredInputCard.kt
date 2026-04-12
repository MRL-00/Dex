package com.remodex.android.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remodex.android.core.model.StructuredUserInputRequest

@Composable
fun StructuredInputCard(
    request: StructuredUserInputRequest,
    onSubmit: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val answers = remember { mutableStateMapOf<String, String>() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Input required",
                style = MaterialTheme.typography.titleMedium,
            )

            request.questions.forEach { question ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (question.header.isNotBlank()) {
                        Text(
                            question.header,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        question.question,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (question.options.isNotEmpty()) {
                        question.options.forEach { option ->
                            val isSelected = answers[question.id] == option.label
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { answers[question.id] = option.label }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isSelected) Icons.Rounded.RadioButtonChecked
                                    else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    option.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (question.isOther || question.options.isEmpty()) {
                        var freeText by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = freeText,
                            onValueChange = {
                                freeText = it
                                answers[question.id] = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Type your answer") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = !question.isSecret,
                        )
                    }
                }
            }

            Button(
                onClick = { onSubmit(answers.toMap()) },
                enabled = request.questions.all { q -> answers.containsKey(q.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Submit")
            }
        }
    }
}
