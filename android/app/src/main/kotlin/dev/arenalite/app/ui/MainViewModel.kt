package dev.arenalite.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.arenalite.app.ArealiteApplication
import dev.arenalite.app.data.SessionRow
import dev.arenalite.core.agent.AgentEvent
import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.WorkspaceEntry
import dev.arenalite.core.model.WorkspaceUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiToolCall(
    val id: String,
    val name: String,
    val input: JsonValue,
    val ok: Boolean? = null,
    val detail: String = "",
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val streamingTools: List<UiToolCall> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

data class WorkspaceUiState(
    val sessions: List<SessionRow> = emptyList(),
    val activeId: String? = null,
    val usage: WorkspaceUsage? = null,
    val files: List<WorkspaceEntry> = emptyList(),
    val currentPath: String = ".",
    val openFile: Pair<String, String>? = null,
    val deviceFreeBytes: Long = 0,
    val totalWorkspaces: Int = 0,
    val totalBytes: Long = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ArealiteApplication).repository

    private val _workspace = MutableStateFlow(WorkspaceUiState())
    val workspace: StateFlow<WorkspaceUiState> = _workspace.asStateFlow()

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private var turnJob: Job? = null
    private var cancelled = false

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { rows ->
                _workspace.value = _workspace.value.copy(sessions = rows)
                if (_workspace.value.activeId == null && rows.isNotEmpty()) select(rows.first().id)
                refreshTotals()
            }
        }
    }

    fun select(sessionId: String) {
        cancelled = false
        _workspace.value = _workspace.value.copy(activeId = sessionId)
        repository.currentSessionId = sessionId
        viewModelScope.launch {
            _chat.value = ChatUiState(messages = repository.messages(sessionId))
            refreshWorkspace(sessionId)
        }
    }

    fun createWorkspace() = viewModelScope.launch {
        val row = repository.createSession("Workspace ${_workspace.value.sessions.size + 1}")
        select(row.id)
    }

    fun forkWorkspace() = viewModelScope.launch {
        val id = _workspace.value.activeId ?: return@launch
        val row = repository.forkSession(id)
        select(row.id)
    }

    fun deleteWorkspace(id: String) = viewModelScope.launch {
        repository.deleteSession(id)
        if (_workspace.value.activeId == id) {
            val next = _workspace.value.sessions.firstOrNull { it.id != id }
            _workspace.value = _workspace.value.copy(activeId = next?.id)
            next?.let { select(it.id) }
        }
        refreshTotals()
    }

    fun openFolder(path: String) {
        val id = _workspace.value.activeId ?: return
        _workspace.value = _workspace.value.copy(currentPath = path, openFile = null)
        _workspace.value = _workspace.value.copy(files = repository.engine.listFiles(id, path))
    }

    fun openFile(path: String) = viewModelScope.launch {
        val id = _workspace.value.activeId ?: return@launch
        val file = repository.engine.readFile(id, path)
        _workspace.value = _workspace.value.copy(openFile = path to (file.text ?: "[biner ${file.size} byte]"))
    }

    fun saveFile(path: String, content: String) = viewModelScope.launch {
        val id = _workspace.value.activeId ?: return@launch
        repository.engine.writeFile(id, path, content, source = "ui")
        refreshWorkspace(id)
        _workspace.value = _workspace.value.copy(openFile = path to content)
    }

    fun send(text: String) {
        val id = _workspace.value.activeId ?: return
        if (text.isBlank() || _chat.value.running) return
        cancelled = false
        repository.currentSessionId = id

        turnJob = viewModelScope.launch {
            _chat.value = _chat.value.copy(running = true, error = null, streamingText = "", streamingTools = emptyList())
            repository.sendUserMessage(id, text)
            _chat.value = _chat.value.copy(messages = repository.messages(id))

            runCatching {
                repository.runTurn(id, _workspace.value.sessions.firstOrNull { it.id == id }?.model ?: "arena-agent-1", { cancelled }) { event ->
                    when (event) {
                        is AgentEvent.TextDelta ->
                            _chat.value = _chat.value.copy(streamingText = _chat.value.streamingText + event.text)

                        is AgentEvent.ToolStart ->
                            _chat.value = _chat.value.copy(
                                streamingTools = _chat.value.streamingTools + UiToolCall(event.id, event.name, event.input),
                            )

                        is AgentEvent.ToolEnd ->
                            _chat.value = _chat.value.copy(
                                streamingTools = _chat.value.streamingTools.map {
                                    if (it.id == event.id) it.copy(ok = event.ok, detail = event.detail) else it
                                },
                            )

                        is AgentEvent.MessagePersisted ->
                            _chat.value = _chat.value.copy(
                                messages = _chat.value.messages + event.message,
                                streamingText = "",
                                streamingTools = emptyList(),
                            )

                        is AgentEvent.Done ->
                            _chat.value = _chat.value.copy(running = false, streamingText = "", streamingTools = emptyList())

                        else -> Unit
                    }
                }
            }.onFailure { error ->
                _chat.value = _chat.value.copy(running = false, error = error.message ?: "gagal")
            }

            _chat.value = _chat.value.copy(messages = repository.messages(id), running = false)
            refreshWorkspace(id)
            refreshTotals()
        }
    }

    fun cancel() {
        cancelled = true
        turnJob?.cancel()
        _chat.value = _chat.value.copy(running = false)
    }

    fun syncPush() = viewModelScope.launch {
        val id = _workspace.value.activeId ?: return@launch
        runCatching { repository.syncPush(id) }
        refreshWorkspace(id)
    }

    fun snapshot() = viewModelScope.launch {
        val id = _workspace.value.activeId ?: return@launch
        repository.engine.snapshot(id)
        refreshWorkspace(id)
    }

    private suspend fun refreshWorkspace(id: String) {
        runCatching {
            val usage = repository.usage(id)
            val path = _workspace.value.currentPath
            _workspace.value = _workspace.value.copy(
                usage = usage,
                files = repository.engine.listFiles(id, path),
                deviceFreeBytes = repository.deviceFreeBytes(),
            )
        }
    }

    private suspend fun refreshTotals() {
        runCatching {
            val global = repository.globalUsage()
            _workspace.value = _workspace.value.copy(
                totalWorkspaces = global.workspaces,
                totalBytes = global.totalBytes,
                deviceFreeBytes = repository.deviceFreeBytes(),
            )
        }
    }
}
