package com.remodex.android.ui.component

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.android.core.attachment.ImageAttachmentPipeline
import com.remodex.android.core.model.ConversationFileChangeSummaryParser
import com.remodex.android.core.model.ConversationMessage
import com.remodex.android.core.model.DiffFileAction
import com.remodex.android.core.model.ImageAttachment
import com.remodex.android.core.model.MessageKind
import com.remodex.android.core.model.MessageRole

private val InlineFileBlue = androidx.compose.ui.graphics.Color(0xFF4C97FF)
private val DiffAdditionGreen = androidx.compose.ui.graphics.Color(0xFF22C55E)
private val DiffDeletionRed = androidx.compose.ui.graphics.Color(0xFFF04444)
private val DiffHunkBlue = androidx.compose.ui.graphics.Color(0xFF94A3B8)

@Composable
fun MessageBubble(message: ConversationMessage) {
    when (message.kind) {
        MessageKind.THINKING -> ThinkingBubble(message)
        MessageKind.TOOL_ACTIVITY -> ToolActivityBubble(message)
        MessageKind.COMMAND_EXECUTION -> CommandExecutionBubble(message)
        else -> {
            when (message.role) {
                MessageRole.USER -> UserBubble(message)
                MessageRole.ASSISTANT -> AssistantMessage(message)
                MessageRole.SYSTEM -> SystemMessage(message)
            }
        }
    }
}

/**
 * User messages: right-aligned bubble with primary background (like iOS).
 */
@Composable
private fun UserBubble(message: ConversationMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            if (message.attachments.isNotEmpty()) {
                UserAttachmentStrip(
                    attachments = message.attachments,
                    modifier = Modifier.padding(bottom = if (message.text.isBlank()) 0.dp else 6.dp),
                )
            }

            if (message.text.isNotBlank()) {
                Card(
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 4.dp,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Column(Modifier.padding(12.dp, 10.dp).animateContentSize()) {
                        RichMessageText(
                            text = message.text,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            textStyle = chatBodyTextStyle(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Assistant messages: left-aligned bubble with surface background.
 */
@Composable
private fun AssistantMessage(message: ConversationMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp, 10.dp).animateContentSize()) {
                RichMessageText(
                    text = message.text,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = chatBodyTextStyle(),
                )
                if (message.isStreaming) {
                    Spacer(Modifier.height(6.dp))
                    StreamingIndicator()
                }
            }
        }
    }
}

/**
 * System messages: subdued, smaller text.
 */
@Composable
private fun SystemMessage(message: ConversationMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun UserAttachmentStrip(
    attachments: List<ImageAttachment>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            val bitmap = remember(attachment.thumbnailBase64) {
                ImageAttachmentPipeline.decodeThumbnailBitmap(attachment.thumbnailBase64)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Attached image",
                    modifier = Modifier
                        .size(70.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble(message: ConversationMessage) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Thinking",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        if (message.isStreaming) {
            Spacer(Modifier.width(8.dp))
            StreamingIndicator()
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
    if (expanded && message.text.isNotBlank()) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun ToolActivityBubble(message: ConversationMessage) {
    val statusLabel = if (message.isStreaming) "running" else "completed"
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            text = message.text.trim(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun CommandExecutionBubble(message: ConversationMessage) {
    val model = remember(message.text) { parseCommandExecutionStatus(message.text) }
    val statusColor = when {
        message.text.startsWith("failed ", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val primaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
        val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        Text(
            buildAnnotatedString {
                append(model.verb)
                append(" ")
                withStyle(SpanStyle(color = secondaryColor)) {
                    append(model.target)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = primaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = model.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
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
    textStyle: TextStyle = chatBodyTextStyle(),
) {
    val clipboardManager = LocalClipboardManager.current
    val blocks = remember(text) { parseMessageBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MessageBlock.Text -> {
                    val formattedText = remember(block.content) { formatInlineMarkdown(block.content) }
                    Text(
                        text = formattedText,
                        style = textStyle,
                        color = contentColor,
                    )
                }
                is MessageBlock.CodeBlock -> {
                    val isDiffBlock = remember(block.language, block.code) {
                        block.language.equals("diff", ignoreCase = true) || looksLikeDiff(block.code)
                    }
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                            if (isDiffBlock) {
                                DiffCodeBlock(
                                    code = block.code,
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 4.dp),
                                )
                            } else {
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
                is MessageBlock.FileChangeGroups -> {
                    FileChangeGroupList(groups = block.groups)
                }
            }
        }
    }
}

@Composable
private fun chatBodyTextStyle(): TextStyle {
    return MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}

private sealed class MessageBlock {
    data class Text(val content: String) : MessageBlock()
    data class CodeBlock(val language: String, val code: String) : MessageBlock()
    data class FileChangeGroups(val groups: List<FileChangeGroup>) : MessageBlock()
}

private fun parseMessageBlocks(text: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val lines = text.lines()
    var i = 0
    val currentText = StringBuilder()

    fun flushTextBlock() {
        if (currentText.isBlank()) return
        val content = currentText.toString().trimEnd()
        val fileChangeGroups = parseFileChangeGroups(content)
        if (fileChangeGroups != null) {
            blocks.add(MessageBlock.FileChangeGroups(fileChangeGroups))
        } else {
            blocks.add(MessageBlock.Text(content))
        }
        currentText.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flushTextBlock()
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
    flushTextBlock()
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

private data class FileChangeEntry(
    val path: String,
    val additions: Int,
    val deletions: Int,
) {
    val compactPath: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')
}

private data class FileChangeGroup(
    val label: String,
    val entries: List<FileChangeEntry>,
)

@Composable
private fun FileChangeGroupList(groups: List<FileChangeGroup>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                group.entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.compactPath,
                            color = InlineFileBlue,
                            style = chatBodyTextStyle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "+${entry.additions}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = DiffAdditionGreen,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "-${entry.deletions}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = DiffDeletionRed,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffCodeBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val neutralTextColor = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth()) {
        code.lines().forEach { rawLine ->
            when (classifyMessageDiffLine(rawLine)) {
                MessageDiffLineKind.META -> Unit
                MessageDiffLineKind.HUNK -> {
                    Spacer(Modifier.height(6.dp))
                }
                else -> {
                    val kind = classifyMessageDiffLine(rawLine)
                    val content = when (kind) {
                        MessageDiffLineKind.ADDITION,
                        MessageDiffLineKind.DELETION -> rawLine.drop(1)
                        MessageDiffLineKind.NEUTRAL -> rawLine.removePrefix(" ")
                        else -> rawLine
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(messageDiffLineBackground(kind)),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(if (kind == MessageDiffLineKind.ADDITION || kind == MessageDiffLineKind.DELETION) 2.dp else 0.dp)
                                .height(18.dp)
                                .background(messageDiffIndicatorColor(kind)),
                        )
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                            color = messageDiffTextColor(kind, neutralTextColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parseFileChangeGroups(text: String): List<FileChangeGroup>? {
    val summary = ConversationFileChangeSummaryParser.parse(text) ?: return null
    return summary.entries
        .groupBy { entry -> entry.action?.label ?: DiffFileAction.EDITED.label }
        .map { (label, entries) ->
            FileChangeGroup(
                label = label,
                entries = entries.map { entry ->
                    FileChangeEntry(
                        path = entry.path,
                        additions = entry.additions,
                        deletions = entry.deletions,
                    )
                },
            )
        }
}

private fun parseCommandExecutionStatus(text: String): CommandExecutionStatus {
    val trimmed = text.trim()
    val parts = trimmed.split(Regex("\\s+"), limit = 2)
    val status = parts.firstOrNull()?.lowercase().orEmpty()
    val command = parts.getOrNull(1).orEmpty().ifBlank { "command" }
    val humanized = humanizeCommand(command, status == "running")
    return CommandExecutionStatus(
        verb = humanized.first,
        target = humanized.second,
        statusLabel = when (status) {
            "running", "completed", "failed", "stopped" -> status
            else -> if (status.isBlank()) "running" else status
        },
    )
}

private fun humanizeCommand(raw: String, isRunning: Boolean): Pair<String, String> {
    val command = unwrapShellCommand(raw)
    val parts = command.split(Regex("\\s+"), limit = 2)
    val tool = parts.firstOrNull()?.substringAfterLast('/')?.lowercase().orEmpty()
    val args = parts.getOrNull(1).orEmpty()

    return when (tool) {
        "cat", "nl", "head", "tail", "sed", "less", "more" ->
            (if (isRunning) "Reading" else "Read") to compactPath(lastPathToken(args, "file"))
        "rg", "grep", "ag", "ack" ->
            (if (isRunning) "Searching" else "Searched") to searchSummary(args)
        "ls" ->
            (if (isRunning) "Listing" else "Listed") to compactPath(lastPathToken(args, "directory"))
        "find", "fd" ->
            (if (isRunning) "Finding" else "Found") to compactPath(lastPathToken(args, "files"))
        "git" -> humanizeGit(args, isRunning)
        else -> (if (isRunning) "Running" else "Ran") to command
    }
}

private fun humanizeGit(args: String, isRunning: Boolean): Pair<String, String> {
    val parts = args.split(Regex("\\s+"), limit = 2)
    return when (parts.firstOrNull()) {
        "status" -> (if (isRunning) "Checking" else "Checked") to "git status"
        "diff" -> (if (isRunning) "Comparing" else "Compared") to "changes"
        "log" -> (if (isRunning) "Viewing" else "Viewed") to "git log"
        "add" -> (if (isRunning) "Staging" else "Staged") to "changes"
        "commit" -> (if (isRunning) "Committing" else "Committed") to "changes"
        "push" -> (if (isRunning) "Pushing" else "Pushed") to "to remote"
        "pull" -> (if (isRunning) "Pulling" else "Pulled") to "from remote"
        "checkout", "switch" -> {
            val branch = parts.getOrNull(1)?.split(Regex("\\s+"))?.lastOrNull().orEmpty()
            (if (isRunning) "Switching to" else "Switched to") to branch.ifBlank { "branch" }
        }
        else -> (if (isRunning) "Running" else "Ran") to "git $args".trim()
    }
}

private fun unwrapShellCommand(command: String): String {
    val prefixes = listOf("bash -lc ", "bash -c ", "zsh -lc ", "zsh -c ", "/bin/bash -lc ", "/bin/zsh -lc ")
    val prefix = prefixes.firstOrNull { command.startsWith(it) } ?: return command
    val nested = command.removePrefix(prefix).trim().trim('"', '\'')
    return nested.substringAfter("&&", nested).trim()
}

private fun lastPathToken(args: String, fallback: String): String {
    return args.split(Regex("\\s+"))
        .asReversed()
        .firstOrNull { it.isNotBlank() && !it.startsWith("-") }
        ?.trim('"', '\'')
        ?: fallback
}

private fun compactPath(path: String): String {
    val components = path.split("/").filter { it.isNotBlank() }
    return if (components.size > 2) {
        components.takeLast(2).joinToString("/")
    } else {
        path
    }
}

private fun searchSummary(args: String): String {
    val tokens = args.split(Regex("\\s+")).filter { it.isNotBlank() }
    val positional = tokens.filterNot { it.startsWith("-") }
    val pattern = positional.firstOrNull() ?: "..."
    val path = positional.getOrNull(1)
    return if (path != null) "for ${pattern.take(30)} in ${compactPath(path.trim('"', '\''))}" else "for ${pattern.take(30)}"
}

private fun looksLikeDiff(code: String): Boolean {
    val lines = code.lines()
    val hasHunk = lines.any { it.startsWith("@@") }
    val additions = lines.count { it.startsWith("+") && !it.startsWith("+++") }
    val deletions = lines.count { it.startsWith("-") && !it.startsWith("---") }
    return hasHunk || (additions > 0 && deletions > 0)
}

private enum class MessageDiffLineKind {
    ADDITION,
    DELETION,
    HUNK,
    META,
    NEUTRAL,
}

private fun classifyMessageDiffLine(line: String): MessageDiffLineKind {
    return when {
        line.startsWith("@@") -> MessageDiffLineKind.HUNK
        line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++") -> MessageDiffLineKind.META
        line.startsWith("+") && !line.startsWith("+++") -> MessageDiffLineKind.ADDITION
        line.startsWith("-") && !line.startsWith("---") -> MessageDiffLineKind.DELETION
        else -> MessageDiffLineKind.NEUTRAL
    }
}

private fun messageDiffTextColor(
    kind: MessageDiffLineKind,
    neutralColor: androidx.compose.ui.graphics.Color,
) = when (kind) {
    MessageDiffLineKind.ADDITION -> DiffAdditionGreen
    MessageDiffLineKind.DELETION -> DiffDeletionRed
    MessageDiffLineKind.HUNK -> DiffHunkBlue
    MessageDiffLineKind.META, MessageDiffLineKind.NEUTRAL -> neutralColor
}

private fun messageDiffIndicatorColor(kind: MessageDiffLineKind) = when (kind) {
    MessageDiffLineKind.ADDITION -> DiffAdditionGreen
    MessageDiffLineKind.DELETION -> DiffDeletionRed
    else -> androidx.compose.ui.graphics.Color.Transparent
}

private fun messageDiffLineBackground(kind: MessageDiffLineKind) = when (kind) {
    MessageDiffLineKind.ADDITION -> DiffAdditionGreen.copy(alpha = 0.12f)
    MessageDiffLineKind.DELETION -> DiffDeletionRed.copy(alpha = 0.12f)
    else -> androidx.compose.ui.graphics.Color.Transparent
}

private data class CommandExecutionStatus(
    val verb: String,
    val target: String,
    val statusLabel: String,
)
