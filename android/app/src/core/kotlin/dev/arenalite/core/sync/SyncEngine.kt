package dev.arenalite.core.sync

import dev.arenalite.core.json.parseJson
import dev.arenalite.core.model.FileManifest
import dev.arenalite.core.model.OpLogEntry
import dev.arenalite.core.util.FileSystem
import dev.arenalite.core.util.nowIso
import dev.arenalite.core.workspace.WorkspaceEngine

/**
 * Remote storage for unlimited cloud sync.
 *
 * Two implementations ship with the app:
 *  - [HttpRemoteBackend]  → any S3-compatible or Arealite-server endpoint
 *  - a local mirror used by tests and by "sync to my own folder" mode
 *
 * Nothing here caps the number or size of files: the workspace is uploaded
 * whole, chunk by chunk, for as long as the user keeps adding to it.
 */
interface RemoteBackend {
    val id: String
    suspend fun ensure(remoteId: String)
    suspend fun put(remoteId: String, key: String, bytes: ByteArray)
    suspend fun get(remoteId: String, key: String): ByteArray?
    suspend fun delete(remoteId: String, key: String)
}

/** In-memory / on-disk mirror. Handy for tests and for offline-first sync. */
class MirrorBackend(private val fs: FileSystem? = null, private val root: String? = null) : RemoteBackend {
    override val id: String = "mirror"
    private val memory = mutableMapOf<String, ByteArray>()

    private fun key(remoteId: String, key: String) = "$remoteId/$key"

    override suspend fun ensure(remoteId: String) {
        if (fs != null && root != null) fs.mkdirs(fs.join(root, remoteId))
    }

    override suspend fun put(remoteId: String, key: String, bytes: ByteArray) {
        memory[key(remoteId, key)] = bytes
        if (fs != null && root != null) {
            val target = fs.join(root, remoteId, key)
            fs.mkdirs(target.substringBeforeLast('/', ""))
            fs.writeBytes(target, bytes)
        }
    }

    override suspend fun get(remoteId: String, key: String): ByteArray? {
        memory[key(remoteId, key)]?.let { return it }
        if (fs != null && root != null) {
            val target = fs.join(root, remoteId, key)
            if (fs.exists(target)) return fs.readBytes(target)
        }
        return null
    }

    override suspend fun delete(remoteId: String, key: String) {
        memory.remove(key(remoteId, key))
        if (fs != null && root != null) fs.delete(fs.join(root, remoteId, key))
    }

    fun keys(): Set<String> = memory.keys.toSet()
}

data class SyncState(
    val sessionId: String,
    val remoteId: String,
    val backend: String,
    val cursor: Long = 0,
    val lastPushedAt: String? = null,
    val lastPulledAt: String? = null,
    val pushedFiles: Int = 0,
    val pulledFiles: Int = 0,
)

data class PushReport(val state: SyncState, val uploaded: Int, val deleted: Int, val uploadedBytes: Long)
data class PullReport(val state: SyncState, val downloaded: Int, val conflicts: List<Conflict>)
data class Conflict(val path: String, val backupPath: String)
data class SyncStatus(val localFiles: Int, val remoteFiles: Int, val pendingUpload: Int, val cursor: Long, val state: SyncState)

/**
 * Manifest-diff sync: hash locally, hash remotely, move only what changed.
 */
/**
 * @param readBytes platform file reader (Android: `File(path).readBytes()`),
 *                  injected so the core stays free of java.io decisions.
 */
class SyncEngine(
    private val engine: WorkspaceEngine,
    private val backend: RemoteBackend,
    private val readBytes: (String) -> ByteArray,
) {
    private val states = mutableMapOf<String, SyncState>()

    fun remoteIdFor(sessionId: String): String = states[sessionId]?.remoteId ?: sessionId

    fun bindRemote(sessionId: String, remoteId: String) {
        states[sessionId] = (states[sessionId] ?: emptyState(sessionId, remoteId)).copy(remoteId = remoteId)
    }

    suspend fun push(sessionId: String, onProgress: (String, Long) -> Unit = { _, _ -> }): PushReport {
        val remoteId = remoteIdFor(sessionId)
        backend.ensure(remoteId)

        val local = engine.manifest(sessionId)
        val remote = readRemoteManifest(remoteId)
        val remoteByPath = remote.files.associateBy { it.path }

        val toUpload = local.files.filter { remoteByPath[it.path]?.hash != it.hash }
        val toDelete = remote.files.filter { entry -> local.files.none { it.path == entry.path } }

        var uploadedBytes = 0L
        for (file in toUpload) {
            val abs = engine.resolve(sessionId, file.path)
            val bytes = readBytes(abs)
            backend.put(remoteId, "files/${file.path}", bytes)
            uploadedBytes += bytes.size
            onProgress(file.path, bytes.size.toLong())
        }
        for (file in toDelete) backend.delete(remoteId, "files/${file.path}")

        val cursor = engine.opsSince(sessionId, 0).cursor
        backend.put(remoteId, MANIFEST_KEY, local.copy(sessionId = remoteId).toJson().toString().encodeToByteArray())
        engine.appendOp(
            sessionId,
            OpLogEntry(
                seq = 0,
                type = "sync.push",
                path = null,
                source = "sync",
                at = nowIso(),
                detail = mapOf("uploaded" to toUpload.size, "deleted" to toDelete.size, "bytes" to uploadedBytes),
            ),
        )

        val state = (states[sessionId] ?: emptyState(sessionId, remoteId)).copy(
            backend = backend.id,
            cursor = cursor,
            lastPushedAt = nowIso(),
            pushedFiles = toUpload.size,
        )
        states[sessionId] = state
        return PushReport(state, toUpload.size, toDelete.size, uploadedBytes)
    }

    suspend fun pull(sessionId: String, strategy: String = "remote-wins"): PullReport {
        val remoteId = remoteIdFor(sessionId)
        val remote = readRemoteManifest(remoteId)
        if (remote.files.isEmpty()) {
            return PullReport(states[sessionId] ?: emptyState(sessionId, remoteId), 0, emptyList())
        }

        val local = engine.manifest(sessionId)
        val localByPath = local.files.associateBy { it.path }
        val conflicts = mutableListOf<Conflict>()
        var downloaded = 0

        for (file in remote.files) {
            val localEntry = localByPath[file.path]
            if (localEntry != null && localEntry.hash == file.hash) continue
            if (localEntry != null && strategy == "remote-wins") {
                val backupPath = "${file.path}.local-${System.currentTimeMillis()}"
                val current = readBytes(engine.resolve(sessionId, file.path))
                engine.writeFile(sessionId, backupPath, current.decodeToString(), source = "sync")
                conflicts.add(Conflict(file.path, backupPath))
            }
            val bytes = backend.get(remoteId, "files/${file.path}") ?: continue
            engine.writeFile(sessionId, file.path, bytes.decodeToString(), source = "sync")
            downloaded += 1
        }

        for (file in local.files) {
            if (remote.files.none { it.path == file.path }) engine.deletePath(sessionId, file.path, source = "sync")
        }

        engine.appendOp(
            sessionId,
            OpLogEntry(
                seq = 0,
                type = "sync.pull",
                path = null,
                source = "sync",
                at = nowIso(),
                detail = mapOf("downloaded" to downloaded, "conflicts" to conflicts.size),
            ),
        )

        val state = (states[sessionId] ?: emptyState(sessionId, remoteId)).copy(
            backend = backend.id,
            cursor = remote.let { engine.opsSince(sessionId, 0).cursor },
            lastPulledAt = nowIso(),
            pulledFiles = downloaded,
        )
        states[sessionId] = state
        return PullReport(state, downloaded, conflicts)
    }

    suspend fun status(sessionId: String): SyncStatus {
        val remoteId = remoteIdFor(sessionId)
        val local = engine.manifest(sessionId)
        val remote = readRemoteManifest(remoteId)
        val remoteByPath = remote.files.associateBy { it.path }
        val pending = local.files.count { remoteByPath[it.path]?.hash != it.hash }
        return SyncStatus(
            localFiles = local.files.size,
            remoteFiles = remote.files.size,
            pendingUpload = pending,
            cursor = states[sessionId]?.cursor ?: 0,
            state = states[sessionId] ?: emptyState(sessionId, remoteId),
        )
    }

    private suspend fun readRemoteManifest(remoteId: String): FileManifest {
        val bytes = backend.get(remoteId, MANIFEST_KEY) ?: return FileManifest(remoteId, "", emptyList())
        return runCatching { FileManifest.fromJson(parseJson(bytes.decodeToString())) }
            .getOrDefault(FileManifest(remoteId, "", emptyList()))
    }

    private fun emptyState(sessionId: String, remoteId: String) =
        SyncState(sessionId, remoteId, backend.id)

    companion object {
        const val MANIFEST_KEY = "arealite/manifest.json"
    }
}
