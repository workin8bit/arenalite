package dev.arenalite.app.data

import android.content.Context
import dev.arenalite.app.agent.AndroidExecutor
import dev.arenalite.core.agent.AgentEvent
import dev.arenalite.core.agent.AgentLoop
import dev.arenalite.core.agent.ToolRegistry
import dev.arenalite.core.json.parseJson
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.QuotaPolicy
import dev.arenalite.core.model.WorkspaceSession
import dev.arenalite.core.model.WorkspaceUsage
import dev.arenalite.core.provider.AnthropicProvider
import dev.arenalite.core.provider.MockProvider
import dev.arenalite.core.provider.OpenAiCompatibleProvider
import dev.arenalite.core.provider.Provider
import dev.arenalite.core.sync.MirrorBackend
import dev.arenalite.core.sync.RemoteBackend
import dev.arenalite.core.sync.SyncEngine
import dev.arenalite.core.sync.SyncStatus
import dev.arenalite.core.util.nowIso
import dev.arenalite.core.workspace.WorkspaceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Bridges the platform-agnostic core to Android: storage, HTTP, settings, Room.
 */
class WorkspaceRepository(
    private val context: Context,
    private val fileSystem: AndroidFileSystem,
    private val database: AppDatabase,
    private val settings: SettingsStore,
) {
    val engine = WorkspaceEngine(fileSystem, QuotaPolicy.UNLIMITED)

    fun observeSessions(): Flow<List<SessionRow>> = database.sessions().observeAll()

    suspend fun createSession(title: String): SessionRow {
        val session = engine.createSession(title, settings.model())
        val row = session.toRow()
        database.sessions().upsert(row)
        return row
    }

    suspend fun forkSession(id: String): SessionRow {
        val copy = engine.forkSession(id)
        val row = copy.toRow()
        database.sessions().upsert(row)
        return row
    }

    suspend fun deleteSession(id: String) {
        engine.deleteSession(id)
        database.messages().deleteFor(id)
        database.syncState().delete(id)
        database.sessions().deleteById(id)
    }

    suspend fun rename(id: String, title: String) {
        engine.updateMeta(id) { it.copy(title = title) }
        database.sessions().findById(id)?.let { database.sessions().update(it.copy(title = title, updatedAt = nowIso())) }
    }

    fun usage(id: String): WorkspaceUsage = engine.usage(id)

    fun globalUsage() = engine.globalUsage()

    /** Device free space, shown beside the ∞ quota so the number means something. */
    fun deviceFreeBytes(): Long = fileSystem.availableBytes()

    // ------------------------------------------------------------------ chat

    suspend fun messages(id: String): List<ChatMessage> =
        database.messages().listFor(id).map { ChatMessage.fromJson(parseJson(it.content)) }

    suspend fun sendUserMessage(id: String, text: String) {
        val seq = database.messages().countFor(id) + 1
        database.messages().insert(
            MessageRow(
                sessionId = id,
                seq = seq,
                role = "user",
                content = ChatMessage.user(text).toJson().toString(),
                createdAt = nowIso(),
            ),
        )
    }

    /**
     * Runs one agent turn, persisting every message as it is produced so a
     * crash mid-turn cannot lose the conversation.
     */
    suspend fun runTurn(
        sessionId: String,
        model: String,
        isCancelled: () -> Boolean,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val history = messages(sessionId)
        val registry = ToolRegistry(engine, sessionId, executor())
        val loop = AgentLoop(resolveProvider(), registry)

        loop.run(history, model, isCancelled) { event ->
            if (event is AgentEvent.MessagePersisted) persist(sessionId, event.message)
            emit(event)
        }
        database.sessions().findById(sessionId)?.let { row ->
            database.sessions().update(row.copy(status = "idle", updatedAt = nowIso(), messageCount = database.messages().countFor(sessionId)))
        }
    }

    private suspend fun persist(sessionId: String, message: ChatMessage) {
        val seq = database.messages().countFor(sessionId) + 1
        database.messages().insert(
            MessageRow(
                sessionId = sessionId,
                seq = seq,
                role = message.role,
                content = message.toJson().toString(),
                createdAt = nowIso(),
            ),
        )
        database.sessions().findById(sessionId)?.let {
            database.sessions().update(it.copy(messageCount = seq, status = "running", updatedAt = nowIso()))
        }
    }

    // --------------------------------------------------------------- sync

    suspend fun syncStatus(sessionId: String): SyncStatus =
        syncEngine().status(sessionId)

    suspend fun syncPush(sessionId: String): Int =
        syncEngine().push(sessionId).also { report ->
            database.syncState().upsert(
                SyncStateRow(
                    sessionId = sessionId,
                    remoteId = report.state.remoteId,
                    backend = report.state.backend,
                    cursor = report.state.cursor,
                    lastPushedAt = report.state.lastPushedAt,
                    pushedFiles = report.state.pushedFiles,
                ),
            )
        }.uploaded

    suspend fun syncPull(sessionId: String): Int = syncEngine().pull(sessionId).downloaded

    private fun syncEngine(): SyncEngine {
        val backend: RemoteBackend = settings.remoteRoot()?.let { root ->
            MirrorBackend(fileSystem, root)
        } ?: MirrorBackend(fileSystem, File(File(fileSystem.root()).parentFile, "sync-remote").absolutePath)
        return SyncEngine(engine, backend) { path -> File(path).readBytes() }
    }

    // ---------------------------------------------------------- providers

    /**
     * Arena-style: the app ships with the operator's gateway URL baked into
     * settings, so users never touch an API key. Point the gateway at your own
     * Arealite server (see `server/`) and every model in the catalogue works.
     */
    private suspend fun resolveProvider(): Provider {
        val http = OkHttpArealiteClient()
        val gateway = settings.gatewayUrl()
        val apiKey = settings.gatewayKey()
        return when {
            gateway.isBlank() -> MockProvider(delayMs = 20)
            settings.providerKind() == "openai" -> OpenAiCompatibleProvider(
                http = http,
                id = "gateway",
                endpoint = gateway,
                apiKey = apiKey,
            )
            else -> AnthropicProvider(http = http, endpoint = gateway, apiKey = apiKey)
        }
    }

    private fun executor() = AndroidExecutor(
        resolvePath = { relative -> runCatching { engine.resolve(currentSessionId, relative) }.getOrDefault("") },
        readText = { path -> File(path).readText() },
        writeText = { path, text -> File(path).apply { parentFile?.mkdirs() }.writeText(text) },
        listDir = { path -> File(path).listFiles()?.map { it.name to it.isDirectory } ?: emptyList() },
        delete = { path -> File(path).deleteRecursively() },
        mkdirs = { path -> File(path).mkdirs() },
    )

    /** Set by the ViewModel before a turn so the executor resolves relative paths. */
    var currentSessionId: String = ""
}

private fun WorkspaceSession.toRow() = SessionRow(
    id = id,
    title = title,
    model = model,
    plan = plan,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
    status = status,
)
