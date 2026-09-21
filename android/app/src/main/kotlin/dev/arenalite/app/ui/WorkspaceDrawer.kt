package dev.arenalite.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arenalite.app.data.SessionRow
import dev.arenalite.app.ui.theme.Accent
import dev.arenalite.app.ui.theme.AccentCyan
import dev.arenalite.app.ui.theme.OkGreen
import dev.arenalite.core.util.formatBytesShort
import dev.arenalite.core.util.formatRelativeTime

@Composable
fun WorkspaceDrawer(
    state: WorkspaceUiState,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(Accent, AccentCyan))),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Arealite", style = MaterialTheme.typography.titleMedium)
                Text("agent workspace · unlimited", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Workspace baru") }
        Spacer(Modifier.height(10.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.sessions, key = { it.id }) { session ->
                SessionRowItem(
                    session = session,
                    active = session.id == state.activeId,
                    onClick = { onSelect(session.id) },
                    onDelete = { onDelete(session.id) },
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(OkGreen.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text("∞ tanpa kuota", color = OkGreen, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${state.totalWorkspaces} workspace · ${formatBytesShort(state.totalBytes)} tersimpan · " +
                    "sisa perangkat ${formatBytesShort(state.deviceFreeBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SessionRowItem(session: SessionRow, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(
            session.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${session.messageCount} pesan · ${formatRelativeTime(session.updatedAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onDelete) { Text("hapus", style = MaterialTheme.typography.bodySmall) }
    }
}
