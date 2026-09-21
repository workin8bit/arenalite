package dev.arenalite.core.workspace

import dev.arenalite.core.json.parseJson
import dev.arenalite.core.json.toJsonValue
import dev.arenalite.core.model.FileManifest
import dev.arenalite.core.model.FileManifestEntry
import dev.arenalite.core.model.OpLogEntry
import dev.arenalite.core.model.QuotaPolicy
import dev.arenalite.core.model.WorkspaceEntry
import dev.arenalite.core.model.WorkspaceSession
import dev.arenalite.core.model.WorkspaceUsage
import dev.arenalite.core.util.FileSystem
import dev.arenalite.core.util.NoSuchWorkspaceException
import dev.arenalite.core.util.PathEscapeException
import dev.arenalite.core.util.WorkspaceFileException
import dev.arenalite.core.util.isProbablyText
import dev.arenalite.core.util.normalizeRelative
import dev.arenalite.core.util.nowIso
import dev.arenalite.core.util.randomHex
import dev.arenalite.core.util.sha256Hex

/**
 * On-device workspace engine — the Android twin of the server's engine.
 *
 * Layout under `fs.root()`:
 *   sessions/<id>/files/       the workspace the agent edits
 *   sessions/<id>/meta.json
 *   sessions/<id>/oplog.jsonl
 *   sessions/<id>/seq
 *   sessions/<id>/snapshots/
 *
 * There is deliberately no size or count check anywhere in here: [QuotaPolicy]
 * is consulted only to produce advisory warnings for the UI.
 */
class WorkspaceEngine(
    private val fs: FileSystem,
    private val policy: QuotaPolicy = QuotaPolicy.UNLIMITED,
) {
    private val base: String get() = fs.join(fs.root(), "sessions")

    fun sessionDir(sessionId: String): String = fs.join(base, sessionId)
    fun rootFor(sessionId: String): String = fs.join(sessionDir(sessionId), "files")

    /** Resolve an untrusted relative path inside a session, rejecting traversal. */
    fun resolve(sessionId: String, relative: String): String {
        val clean = normalizeRelative(relative)
        return fs.join(rootFor(sessionId), clean)
    }

    // ------------------------------------------------------------- lifecycle

    fun createSession(title: String = "Workspace baru", model: String = "arena-agent-1"): WorkspaceSession {
        fs.mkdirs(base)
        val stamp = nowIso().take(10).replace("-", "")
        val id = "ws_${stamp}_${randomHex(4)}"
        fs.mkdirs(rootFor(id))
        fs.mkdirs(fs.join(sessionDir(id), "snapshots"))
        val session = WorkspaceSession(
            id = id,
            title = title,
            model = model,
            plan = policy.id,
            createdAt = nowIso(),
            updatedAt = nowIso(),
        )
        writeMeta(session)
        writeFile(id, "README.md", seedReadme(title))
        appendOp(id, OpLogEntry(0, "session.create", null, "system", nowIso(), mapOf("title" to title)))
        return session
    }

    fun readMeta(sessionId: String): WorkspaceSession {
        val file = fs.join(sessionDir(sessionId), "meta.json")
        if (!fs.exists(file)) throw NoSuchWorkspaceException(sessionId)
        return WorkspaceSession.fromJson(parseJson(fs.readText(file)))
    }

    fun writeMeta(session: WorkspaceSession): WorkspaceSession {
        val updated = session.copy(updatedAt = nowIso())
        fs.mkdirs(sessionDir(session.id))
        fs.writeText(fs.join(sessionDir(session.id), "meta.json"), updated.toJson().toString())
        return updated
    }

    fun updateMeta(sessionId: String, transform: (WorkspaceSession) -> WorkspaceSession): WorkspaceSession =
        writeMeta(transform(readMeta(sessionId)))

    fun listSessions(): List<WorkspaceSession> {
        if (!fs.exists(base)) return emptyList()
        return fs.list(base)
            .filter { it.isDirectory }
            .mapNotNull { runCatching { readMeta(it.name) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    /** Unlimited also means unlimited clones: a fork is a full independent copy. */
    fun forkSession(sessionId: String, title: String? = null): WorkspaceSession {
        val source = readMeta(sessionId)
        val copy = createSession(title ?: "${source.title} (copy)", source.model)
        copyTree(rootFor(sessionId), rootFor(copy.id))
        appendOp(copy.id, OpLogEntry(0, "session.fork", null, "system", nowIso(), mapOf("from" to sessionId)))
        return copy
    }

    fun deleteSession(sessionId: String) {
        readMeta(sessionId) // throws when unknown
        fs.delete(sessionDir(sessionId))
    }

    // ----------------------------------------------------------------- files

    fun listFiles(sessionId: String, relative: String = "."): List<WorkspaceEntry> {
        val abs = resolve(sessionId, relative)
        if (!fs.exists(abs)) return emptyList()
        val prefix = normalizeRelative(relative)
        return fs.list(abs)
            .map { entry ->
                WorkspaceEntry(
                    name = entry.name,
                    path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}",
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modifiedAt = entry.modifiedAt,
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    data class FileContent(
        val path: String,
        val size: Long,
        val text: String?,
        val truncated: Boolean,
        val hash: String,
        val modifiedAt: Long,
    )

    fun readFile(sessionId: String, relative: String, maxBytes: Int = MAX_READ_BYTES): FileContent {
        val abs = resolve(sessionId, relative)
        if (fs.isDirectory(abs)) throw WorkspaceFileException("$relative adalah direktori", "EISDIR")
        if (!fs.exists(abs)) throw WorkspaceFileException("$relative tidak ditemukan", "ENOENT")
        val bytes = fs.readBytes(abs)
        return FileContent(
            path = normalizeRelative(relative),
            size = bytes.size.toLong(),
            text = if (isProbablyText(bytes)) bytes.copyOf(minOf(bytes.size, maxBytes)).decodeToString() else null,
            truncated = bytes.size > maxBytes,
            hash = sha256Hex(bytes),
            modifiedAt = fs.modifiedAt(abs),
        )
    }

    /**
     * The single write path: agent tool, UI editor and sync pull all land here so
     * the oplog and usage accounting cannot drift.
     */
    fun writeFile(
        sessionId: String,
        relative: String,
        content: String,
        source: String = "api",
    ): WriteResult {
        val usage = usage(sessionId)
        val incoming = content.encodeToByteArray().size.toLong()
        val decision = policy.evaluate(usage.totalBytes, incoming)
        if (!decision.ok) throw WorkspaceFileException("Kuota terlampaui", "EQUOTA")

        val abs = resolve(sessionId, relative)
        val created = !fs.exists(abs)
        val previousHash = if (created) null else sha256Hex(fs.readBytes(abs))
        fs.mkdirs(fs.join(fs.root(), parentOf(abs)))
        fs.writeText(abs, content)

        appendOp(
            sessionId,
            OpLogEntry(
                seq = 0,
                type = "file.write",
                path = normalizeRelative(relative),
                source = source,
                at = nowIso(),
                detail = mapOf(
                    "bytes" to incoming,
                    "hash" to sha256Hex(content),
                    "previousHash" to previousHash,
                    "created" to created,
                ),
            ),
        )
        touch(sessionId)
        return WriteResult(normalizeRelative(relative), incoming, created, fs.size(abs), decision.warnings)
    }

    fun deletePath(sessionId: String, relative: String, source: String = "api"): Boolean {
        val abs = resolve(sessionId, relative)
        if (abs == rootFor(sessionId)) throw WorkspaceFileException("Tidak bisa menghapus akar workspace", "EROOT")
        val existed = fs.exists(abs)
        fs.delete(abs)
        appendOp(sessionId, OpLogEntry(0, "file.delete", normalizeRelative(relative), source, nowIso(), mapOf("existed" to existed)))
        touch(sessionId)
        return existed
    }

    fun mkdir(sessionId: String, relative: String, source: String = "api") {
        val abs = resolve(sessionId, relative)
        fs.mkdirs(abs)
        appendOp(sessionId, OpLogEntry(0, "dir.create", normalizeRelative(relative), source, nowIso()))
    }

    fun movePath(sessionId: String, from: String, to: String, source: String = "api") {
        fs.rename(resolve(sessionId, from), resolve(sessionId, to))
        appendOp(sessionId, OpLogEntry(0, "file.move", normalizeRelative(to), source, nowIso(), mapOf("from" to from)))
    }

    fun snapshot(sessionId: String, label: String = "snap_${randomHex(3)}"): SnapshotResult {
        readMeta(sessionId)
        val dest = fs.join(sessionDir(sessionId), "snapshots", label)
        fs.mkdirs(dest)
        copyTree(rootFor(sessionId), dest)
        val count = walk(dest).size
        appendOp(sessionId, OpLogEntry(0, "snapshot.create", null, "system", nowIso(), mapOf("label" to label, "files" to count)))
        return SnapshotResult(label, count)
    }

    // ----------------------------------------------------------- usage & ops

    fun usage(sessionId: String): WorkspaceUsage {
        val meta = readMeta(sessionId)
        val root = rootFor(sessionId)
        val files = walk(root)
        val dirs = countDirs(root)
        var total = 0L
        var largest = 0L
        for (f in files) {
            total += f.size
            if (f.size > largest) largest = f.size
        }
        return WorkspaceUsage(
            sessionId = sessionId,
            files = files.size,
            directories = dirs,
            totalBytes = total,
            largestFileBytes = largest,
            messageCount = meta.messageCount,
            policy = policy,
        )
    }

    fun globalUsage(): GlobalUsage {
        val sessions = listSessions()
        var files = 0
        var bytes = 0L
        for (s in sessions) {
            val u = usage(s.id)
            files += u.files
            bytes += u.totalBytes
        }
        return GlobalUsage(sessions.size, files, bytes, policy)
    }

    fun appendOp(sessionId: String, entry: OpLogEntry): OpLogEntry {
        val seq = nextSeq(sessionId)
        val record = entry.copy(seq = seq, at = if (entry.at.isEmpty()) nowIso() else entry.at)
        fs.mkdirs(sessionDir(sessionId))
        fs.appendText(fs.join(sessionDir(sessionId), "oplog.jsonl"), record.toJson().toString() + "\n")
        return record
    }

    private fun nextSeq(sessionId: String): Long {
        val file = fs.join(sessionDir(sessionId), "seq")
        val current = if (fs.exists(file)) fs.readText(file).trim().toLongOrNull() ?: 0L else 0L
        val next = current + 1
        fs.writeText(file, next.toString())
        return next
    }

    fun opsSince(sessionId: String, cursor: Long = 0): OpLogPage {
        val file = fs.join(sessionDir(sessionId), "oplog.jsonl")
        if (!fs.exists(file)) return OpLogPage(cursor, emptyList())
        val ops = fs.readText(file)
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { OpLogEntry.fromJson(parseJson(it)) }.getOrNull() }
            .filter { it.seq > cursor }
            .toList()
        return OpLogPage(ops.lastOrNull()?.seq ?: cursor, ops)
    }

    /** Materialised file list with hashes — what the sync engine diffs against. */
    fun manifest(sessionId: String): FileManifest {
        val root = rootFor(sessionId)
        val entries = walk(root).map { file ->
            val rel = relativize(root, file.path)
            FileManifestEntry(
                path = rel,
                size = file.size,
                hash = sha256Hex(fs.readBytes(file.path)),
                modifiedAt = file.modifiedAt,
            )
        }.sortedBy { it.path }
        return FileManifest(sessionId, nowIso(), entries)
    }

    // -------------------------------------------------------------- internal

    data class WriteResult(val path: String, val bytes: Long, val created: Boolean, val size: Long, val warnings: List<String>)
    data class SnapshotResult(val label: String, val files: Int)
    data class OpLogPage(val cursor: Long, val ops: List<OpLogEntry>)
    data class GlobalUsage(val workspaces: Int, val files: Int, val totalBytes: Long, val policy: QuotaPolicy)

    private fun touch(sessionId: String) {
        runCatching { updateMeta(sessionId) { it } }
    }

    private data class WalkFile(val path: String, val size: Long, val modifiedAt: Long)

    private fun walk(dir: String): List<WalkFile> {
        if (!fs.exists(dir)) return emptyList()
        val out = mutableListOf<WalkFile>()
        val stack = ArrayDeque<String>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (entry in fs.list(current)) {
                if (entry.isDirectory) stack.addLast(entry.path) else out.add(WalkFile(entry.path, entry.size, entry.modifiedAt))
            }
        }
        return out
    }

    private fun countDirs(dir: String): Int {
        if (!fs.exists(dir)) return 0
        var n = 0
        val stack = ArrayDeque<String>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (entry in fs.list(current)) {
                if (entry.isDirectory) {
                    n += 1
                    stack.addLast(entry.path)
                }
            }
        }
        return n
    }

    private fun copyTree(src: String, dest: String) {
        if (!fs.exists(src)) return
        fs.mkdirs(dest)
        for (entry in fs.list(src)) {
            val target = fs.join(dest, entry.name)
            if (entry.isDirectory) copyTree(entry.path, target) else fs.writeBytes(target, fs.readBytes(entry.path))
        }
    }

    private fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "" else path.substring(0, idx)
    }

    private fun relativize(root: String, path: String): String {
        val trimmedRoot = if (root.endsWith('/')) root else "$root/"
        return if (path.startsWith(trimmedRoot)) path.substring(trimmedRoot.length) else path
    }

    companion object {
        const val MAX_READ_BYTES = 1024 * 1024

        private fun seedReadme(title: String): String = """
            |# $title
            |
            |Workspace ini **tidak punya kuota**: simpan file sebesar apa pun, buat session sebanyak apa pun.
            |
            |- Agent punya tools: read_file, write_file, edit_file, list_files, delete_file, exec
            |- Setiap perubahan dicatat di `oplog.jsonl` dan bisa disinkronkan ke cloud
            |
            |Coba minta agent: "buatkan script python yang menghitung fibonacci lalu jalankan".
        """.trimMargin()
    }
}

/** Guard for callers that pass a raw path from the UI instead of a relative one. */
fun WorkspaceEngine.safeResolve(sessionId: String, relative: String): String = try {
    resolve(sessionId, relative)
} catch (e: PathEscapeException) {
    throw WorkspaceFileException(e.message ?: "path tidak valid", "EPATHOUTSIDE")
}

fun WorkspaceEngine.writeChecked(sessionId: String, relative: String, content: String, source: String = "api"): WorkspaceEngine.WriteResult =
    try {
        writeFile(sessionId, relative, content, source)
    } catch (e: PathEscapeException) {
        throw WorkspaceFileException(e.message ?: "path tidak valid", "EPATHOUTSIDE")
    }

/** Convenience for logging structured payloads into the oplog. */
fun WorkspaceEngine.logStructured(sessionId: String, type: String, detail: Map<String, Any?>, source: String = "system") {
    appendOp(sessionId, OpLogEntry(0, type, null, source, nowIso(), detail.mapValues { toJsonValue(it.value) }))
}
