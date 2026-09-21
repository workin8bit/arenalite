package dev.arenalite.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.ContentBlock
import dev.arenalite.core.model.WorkspaceEntry
import dev.arenalite.core.model.WorkspaceUsage
import dev.arenalite.core.util.formatBytesShort
import dev.arenalite.core.util.formatLimit
import dev.arenalite.app.ui.theme.Accent
import dev.arenalite.app.ui.theme.AccentCyan
import dev.arenalite.app.ui.theme.OkGreen

/**
 * Chat + workspace panel. On a phone the file browser is a tab rather than a
 * third column, but it is the same engine and the same tool cards as the web UI.
 */
@Composable
fun ChatScreen(
    chat: ChatUiState,
    hasWorkspace: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenFiles: () -> Unit,
    onSync: () -> Unit,
    onSnapshot: () -> Unit,
    files: List<WorkspaceEntry>,
    currentPath: String,
    openFile: Pair<String, String>?,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    usage: WorkspaceUsage?,
    deviceFreeBytes: Long,
    onFork: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("File") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Pemakaian") })
        }

        when (tab) {
            0 -> ChatTab(chat, hasWorkspace, onSend, onCancel)
            1 -> FilesTab(files, currentPath, openFile, onOpenFolder, onOpenFile, onSaveFile, onOpenFiles, onFork)
            else -> UsageTab(usage, deviceFreeBytes, onSync, onSnapshot)
        }
    }
}

// ---------------------------------------------------------------------- chat

@Composable
private fun ChatTab(chat: ChatUiState, hasWorkspace: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(chat.messages.size, chat.streamingText) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!hasWorkspace) {
                EmptyHint("Buat workspace dulu", "Tekan tombol Workspace, lalu minta agent membuat file atau menjalankan perintah.")
            } else if (chat.messages.isEmpty() && !chat.running) {
                EmptyHint(
                    "Workspace ini belum punya riwayat",
                    "Tidak ada batas kuota — file sebesar apa pun boleh disimpan di sini.",
                )
            }

            for (message in chat.messages) MessageBubble(message)

            if (chat.running || chat.streamingText.isNotEmpty() || chat.streamingTools.isNotEmpty()) {
                AssistantBubble {
                    if (chat.streamingText.isNotEmpty()) Text(chat.streamingText)
                    for (tool in chat.streamingTools) ToolCard(tool.name, tool.ok, tool.detail)
                    if (chat.streamingText.isEmpty() && chat.streamingTools.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("sedang berpikir…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            chat.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                placeholder = { Text("Minta agent membuat file, menjalankan perintah…") },
                enabled = hasWorkspace,
            )
            Spacer(Modifier.width(8.dp))
            if (chat.running) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Hentikan") }
            } else {
                IconButton(onClick = {
                    if (draft.isNotBlank()) {
                        onSend(draft.trim())
                        draft = ""
                    }
                }, enabled = hasWorkspace && draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.role == "user" && message.blocks.none { it is ContentBlock.ToolResult }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Accent.copy(alpha = 0.16f))
                    .padding(10.dp),
            ) { Text(message.text) }
        }
        return
    }

    if (message.role != "assistant") {
        // tool_result carrier messages are rendered by their tool card
        return
    }

    AssistantBubble {
        val text = message.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        if (text.isNotBlank()) Text(text)
        for (block in message.blocks.filterIsInstance<ContentBlock.ToolUse>()) {
            ToolCard(block.name, ok = null, detail = "")
        }
    }
}

@Composable
private fun AssistantBubble(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Accent, AccentCyan))),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ToolCard(name: String, ok: Boolean?, detail: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                when (ok) {
                    true -> "selesai"
                    false -> "gagal"
                    null -> "berjalan…"
                },
                color = when (ok) {
                    true -> OkGreen
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded && detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail.take(2000),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

// --------------------------------------------------------------------- files

@Composable
private fun FilesTab(
    files: List<WorkspaceEntry>,
    currentPath: String,
    openFile: Pair<String, String>?,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onOpenFiles: () -> Unit,
    onFork: () -> Unit,
) {
    if (openFile != null) {
        var draft by remember(openFile.first) { mutableStateOf(openFile.second) }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenFolder(currentPath) }) { Text("← kembali") }
                Text(openFile.first, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSaveFile(openFile.first, draft) }) { Text("simpan") }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.labelSmall,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val parent = currentPath.substringBeforeLast('/', "")
                onOpenFolder(parent.ifBlank { "." })
            }) { Text("↑") }
            Text(
                if (currentPath == ".") "workspace/" else "$currentPath/",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFork) { Text("fork") }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.path }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (entry.isDirectory) onOpenFolder(entry.path) else onOpenFile(entry.path) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    if (!entry.isDirectory) {
                        Text(formatBytesShort(entry.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- usage

@Composable
private fun UsageTab(usage: WorkspaceUsage?, deviceFreeBytes: Long, onSync: () -> Unit, onSnapshot: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UsageLine("File", (usage?.files ?: 0).toString())
        UsageLine("Direktori", (usage?.directories ?: 0).toString())
        UsageLine("Total workspace", formatBytesShort(usage?.totalBytes ?: 0))
        UsageLine("File terbesar", formatBytesShort(usage?.largestFileBytes ?: 0))
        UsageLine("Batas workspace", formatLimit(usage?.policy?.maxBytesPerWorkspace ?: Long.MAX_VALUE))
        UsageLine("Penegakan kuota", if (usage?.policy?.enforce == true) "aktif" else "mati")
        UsageLine("Sisa penyimpanan perangkat", formatBytesShort(deviceFreeBytes))

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSync) { Text("Sync sekarang") }
            Button(onClick = onSnapshot) { Text("Snapshot") }
        }
        Text(
            "Sync berjalan otomatis tiap 6 jam. Tidak ada batas ukuran: workspace sebesar apa pun tetap diunggah utuh.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun QuotaBadge(bytes: Long) {
    Box(
        Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(OkGreen.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("${formatBytesShort(bytes)} / ∞", color = OkGreen, style = MaterialTheme.typography.bodySmall)
    }
}
