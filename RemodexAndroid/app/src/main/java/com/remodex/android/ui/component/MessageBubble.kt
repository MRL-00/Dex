package com.remodex.android.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.android.core.model.ConversationMessage
import com.remodex.android.core.model.MessageKind
import com.remodex.android.core.model.MessageRole

@Composable
fun MessageBubble(message: ConversationMessage) {
    val isUser = message.role == MessageRole.USER
    val containerColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surface
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    when (message.kind) {
        MessageKind.THINKING -> ThinkingBubble(message)
        MessageKind.TOOL_ACTIVITY -> ToolActivityBubble(message)
        else -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.95f),
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 20.dp,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = if (message.role == MessageRole.ASSISTANT) 0.5.dp else 0.dp,
                    ),
                ) {
                    Column(Modifier.padding(14.dp).animateContentSize()) {
                        RichMessageText(
                            text = message.text,
                            contentColor = contentColor,
                        )
                        if (message.isStreaming) {
                            Spacer(Modifier.height(8.dp))
                            StreamingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(0.95f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.isStreaming) {
                    Spacer(Modifier.width(8.dp))
                    StreamingIndicator()
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded && message.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ToolActivityBubble(message: ConversationMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.secondary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        if (message.isStreaming) {
            Spacer(Modifier.width(6.dp))
            StreamingIndicator()
        }
    }
}

@Composable
fun StreamingIndicator() {
    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
    )
}

@Composable
fun RichMessageText(
    text: String,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val blocks = parseMessageBlocks(text)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MessageBlock.Text -> {
                    Text(
                        text = formatInlineMarkdown(block.content),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                    )
                }
                is MessageBlock.CodeBlock -> {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        ),
                    ) {
                        Column {
                            if (block.language.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        block.language,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(block.code))
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class MessageBlock {
    data class Text(val content: String) : MessageBlock()
    data class CodeBlock(val language: String, val code: String) : MessageBlock()
}

private fun parseMessageBlocks(text: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val lines = text.lines()
    var i = 0
    val currentText = StringBuilder()

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            if (currentText.isNotBlank()) {
                blocks.add(MessageBlock.Text(currentText.toString().trimEnd()))
                currentText.clear()
            }
            val language = line.trimStart().removePrefix("```").trim()
            val codeLines = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.appendLine(lines[i])
                i++
            }
            blocks.add(MessageBlock.CodeBlock(language, codeLines.toString().trimEnd()))
            i++ // skip closing ```
        } else {
            currentText.appendLine(line)
            i++
        }
    }
    if (currentText.isNotBlank()) {
        blocks.add(MessageBlock.Text(currentText.toString().trimEnd()))
    }
    return blocks
}

private fun formatInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val chars = text.toCharArray()
        while (i < chars.size) {
            when {
                // Bold: **text**
                i + 1 < chars.size && chars[i] == '*' && chars[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(chars[i])
                        i++
                    }
                }
                // Italic: *text*
                chars[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(chars[i])
                        i++
                    }
                }
                // Inline code: `text`
                chars[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(chars[i])
                        i++
                    }
                }
                else -> {
                    append(chars[i])
                    i++
                }
            }
        }
    }
}
