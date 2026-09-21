package dev.arenalite.core.model

import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.json.arr
import dev.arenalite.core.json.asList
import dev.arenalite.core.json.asMap
import dev.arenalite.core.json.asString
import dev.arenalite.core.json.boolOr
import dev.arenalite.core.json.get
import dev.arenalite.core.json.intOr
import dev.arenalite.core.json.jsonArr
import dev.arenalite.core.json.jsonObj
import dev.arenalite.core.json.listOr
import dev.arenalite.core.json.longOr
import dev.arenalite.core.json.obj
import dev.arenalite.core.json.stringOr
import dev.arenalite.core.json.stringOrNull
import dev.arenalite.core.json.toAny
import dev.arenalite.core.json.toJsonValue

/**
 * Unlimited-by-construction plan.
 *
 * `enforce` is false for every plan the app ships with: we measure storage so the
 * UI can show "1.4 GB / ∞", but no code path refuses a write because of size.
 */
data class QuotaPolicy(
    val id: String = "unlimited",
    val label: String = "Unlimited",
    val enforce: Boolean = false,
    val maxWorkspaces: Long = Long.MAX_VALUE,
    val maxBytesPerWorkspace: Long = Long.MAX_VALUE,
    val maxBytesPerFile: Long = Long.MAX_VALUE,
    val maxSessions: Long = Long.MAX_VALUE,
    val retentionDays: Long = Long.MAX_VALUE,
) {
    fun evaluate(currentBytes: Long, incomingBytes: Long): QuotaDecision {
        val warnings = mutableListOf<String>()
        if (currentBytes + incomingBytes > SOFT_LIMIT_BYTES) {
            warnings.add("Workspace melewati 1 GB. Tetap diizinkan — tidak ada kuota pada workspace ini.")
        }
        val ok = !enforce || (currentBytes + incomingBytes <= maxBytesPerWorkspace && incomingBytes <= maxBytesPerFile)
        return QuotaDecision(ok, warnings, id)
    }

    companion object {
        const val SOFT_LIMIT_BYTES: Long = 1L * 1024 * 1024 * 1024
        val UNLIMITED = QuotaPolicy()
    }
}

data class QuotaDecision(val ok: Boolean, val warnings: List<String>, val plan: String)

data class WorkspaceSession(
    val id: String,
    val title: String,
    val model: String = "arena-agent-1",
    val plan: String = "unlimited",
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int = 0,
    val status: String = "idle",
) {
    fun toJson(): JsonValue = jsonObj(
        "id" to id,
        "title" to title,
        "model" to model,
        "plan" to plan,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "messageCount" to messageCount,
        "status" to status,
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceSession = WorkspaceSession(
            id = value.stringOr("id"),
            title = value.stringOr("title", "Workspace"),
            model = value.stringOr("model", "arena-agent-1"),
            plan = value.stringOr("plan", "unlimited"),
            createdAt = value.stringOr("createdAt"),
            updatedAt = value.stringOr("updatedAt"),
            messageCount = value.intOr("messageCount"),
            status = value.stringOr("status", "idle"),
        )
    }
}

data class WorkspaceEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
) {
    fun toJson(): JsonValue = jsonObj(
        "name" to name,
        "path" to path,
        "type" to if (isDirectory) "dir" else "file",
        "size" to size,
        "modifiedAt" to modifiedAt,
    )
}

data class WorkspaceUsage(
    val sessionId: String,
    val files: Int,
    val directories: Int,
    val totalBytes: Long,
    val largestFileBytes: Long,
    val messageCount: Int,
    val policy: QuotaPolicy,
) {
    fun toJson(): JsonValue = jsonObj(
        "sessionId" to sessionId,
        "files" to files,
        "directories" to directories,
        "totalBytes" to totalBytes,
        "largestFileBytes" to largestFileBytes,
        "messageCount" to messageCount,
        "policy" to jsonObj(
            "id" to policy.id,
            "enforce" to policy.enforce,
            "maxBytesPerWorkspace" to unlimitedOrNull(policy.maxBytesPerWorkspace),
        ),
    )

    private fun unlimitedOrNull(value: Long): Any? = if (value == Long.MAX_VALUE) null else value
}

/** One line of the append-only workspace log. The sync engine diffs on `seq`. */
data class OpLogEntry(
    val seq: Long,
    val type: String,
    val path: String?,
    val source: String,
    val at: String,
    val detail: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JsonValue = jsonObj(
        "seq" to seq,
        "type" to type,
        "path" to path,
        "source" to source,
        "at" to at,
        "detail" to toJsonValue(detail),
    )

    companion object {
        fun fromJson(value: JsonValue): OpLogEntry = OpLogEntry(
            seq = value.longOr("seq"),
            type = value.stringOr("type"),
            path = value.stringOrNull("path"),
            source = value.stringOr("source", "system"),
            at = value.stringOr("at"),
            detail = value.get("detail").asMap().mapValues { it.value.toAny() },
        )
    }
}

data class FileManifestEntry(val path: String, val size: Long, val hash: String, val modifiedAt: Long) {
    fun toJson(): JsonValue = jsonObj("path" to path, "size" to size, "hash" to hash, "modifiedAt" to modifiedAt)

    companion object {
        fun fromJson(value: JsonValue): FileManifestEntry = FileManifestEntry(
            path = value.stringOr("path"),
            size = value.longOr("size"),
            hash = value.stringOr("hash"),
            modifiedAt = value.longOr("modifiedAt"),
        )
    }
}

data class FileManifest(val sessionId: String, val generatedAt: String, val files: List<FileManifestEntry>) {
    fun toJson(): JsonValue = jsonObj(
        "sessionId" to sessionId,
        "generatedAt" to generatedAt,
        "files" to jsonArr(files.map { it.toJson() }),
    )

    companion object {
        fun fromJson(value: JsonValue): FileManifest = FileManifest(
            sessionId = value.stringOr("sessionId"),
            generatedAt = value.stringOr("generatedAt"),
            files = value.listOr("files").map { FileManifestEntry.fromJson(it) },
        )
    }
}

// ------------------------------------------------------------ chat protocol

/** Content blocks mirror the Anthropic wire format so providers need no mapping. */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolUse(val id: String, val name: String, val input: Map<String, Any?>) : ContentBlock()
    data class ToolResult(
        val toolUseId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock()

    fun toJson(): JsonValue = when (this) {
        is Text -> jsonObj("type" to "text", "text" to text)
        is ToolUse -> jsonObj("type" to "tool_use", "id" to id, "name" to name, "input" to toJsonValue(input))
        is ToolResult -> jsonObj(
            "type" to "tool_result",
            "tool_use_id" to toolUseId,
            "name" to name,
            "content" to content,
            "is_error" to isError,
        )
    }

    companion object {
        fun fromJson(value: JsonValue): ContentBlock = when (value.stringOr("type")) {
            "tool_use" -> ToolUse(
                id = value.stringOr("id"),
                name = value.stringOr("name"),
                input = value.get("input").asMap().mapValues { it.value.toAny() },
            )
            "tool_result" -> ToolResult(
                toolUseId = value.stringOr("tool_use_id"),
                name = value.stringOr("name"),
                content = value.get("content").asString(),
                isError = value.boolOr("is_error"),
            )
            else -> Text(value.stringOr("text"))
        }
    }
}

data class ChatMessage(val role: String, val blocks: List<ContentBlock>) {
    val text: String get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    fun toJson(): JsonValue = jsonObj(
        "role" to role,
        "content" to jsonArr(blocks.map { it.toJson() }),
    )

    companion object {
        fun user(text: String) = ChatMessage("user", listOf(ContentBlock.Text(text)))
        fun assistant(blocks: List<ContentBlock>) = ChatMessage("assistant", blocks)
        fun toolResults(results: List<ContentBlock.ToolResult>) = ChatMessage("user", results)

        fun fromJson(value: JsonValue): ChatMessage {
            val role = value.stringOr("role", "user")
            val content = value.get("content")
            val blocks = when (content) {
                is JsonValue.Arr -> content.items.map { ContentBlock.fromJson(it) }
                is JsonValue.Str -> listOf(ContentBlock.Text(content.value))
                else -> emptyList()
            }
            return ChatMessage(role, blocks)
        }
    }
}

data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: JsonValue,
) {
    fun toJson(): JsonValue = jsonObj("name" to name, "description" to description, "input_schema" to inputSchema)
}

data class ModelInfo(val id: String, val label: String, val context: Int, val isDefault: Boolean = false) {
    companion object {
        fun fromJson(value: JsonValue): ModelInfo = ModelInfo(
            id = value.stringOr("id"),
            label = value.stringOr("label"),
            context = value.intOr("context", 128000),
            isDefault = value.boolOr("default"),
        )
    }
}
