package dev.arenalite.core.agent

import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.json.asString
import dev.arenalite.core.json.encodeJson
import dev.arenalite.core.json.get
import dev.arenalite.core.json.jsonObj
import dev.arenalite.core.json.obj
import dev.arenalite.core.json.stringOr
import dev.arenalite.core.json.toJsonValue
import dev.arenalite.core.model.ToolSchema
import dev.arenalite.core.workspace.WorkspaceEngine

/** Outcome of a single tool call. */
sealed class ToolOutcome {
    data class Success(val result: JsonValue) : ToolOutcome()
    data class Failure(val error: String, val code: String? = null) : ToolOutcome()

    val ok: Boolean get() = this is Success

    fun toContentString(): String = when (this) {
        is Success -> result.toString()
        is Failure -> jsonObj("error" to error, "code" to code).toString()
    }
}

fun interface ToolHandler {
    suspend fun invoke(args: JsonValue): ToolOutcome
}

/**
 * Command execution hook. Android cannot fork a real shell, so the app ships an
 * in-process interpreter (see `app/agent/AndroidExecutor.kt`) and the same
 * interface is used by the JVM tests with a stub.
 */
fun interface CommandExecutor {
    suspend fun exec(command: String, cwd: String, timeoutMs: Long): ExecResult
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/**
 * The tool surface exposed to the model. Mirrors the server's registry 1:1 so a
 * conversation started on the phone can continue on the web without retraining
 * the model's expectations.
 */
open class ToolRegistry(
    private val engine: WorkspaceEngine,
    private val sessionId: String,
    private val executor: CommandExecutor? = null,
) {
    val handlers: Map<String, ToolHandler> = buildMap {
        put("list_files", ToolHandler { args -> guard { listFiles(args) } })
        put("read_file", ToolHandler { args -> guard { readFile(args) } })
        put("write_file", ToolHandler { args -> guard { writeFile(args) } })
        put("edit_file", ToolHandler { args -> guard { editFile(args) } })
        put("delete_file", ToolHandler { args -> guard { deleteFile(args) } })
        put("make_dir", ToolHandler { args -> guard { makeDir(args) } })
        put("exec", ToolHandler { args -> guard { exec(args) } })
        put("workspace_usage", ToolHandler { guard { workspaceUsage() } })
    }

    open suspend fun call(name: String, args: JsonValue): ToolOutcome {
        val handler = handlers[name] ?: return ToolOutcome.Failure("Tool tidak dikenal: $name", "ETOOL")
        return handler.invoke(args)
    }

    // -------------------------------------------------------------- handlers

    private fun listFiles(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path", ".")
        val entries = engine.listFiles(sessionId, path)
        return ToolOutcome.Success(
            jsonObj("path" to path, "entries" to toJsonValue(entries.map { it.toJson() })),
        )
    }

    private fun readFile(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path")
        if (path.isEmpty()) return ToolOutcome.Failure("path wajib diisi", "EARGS")
        val file = engine.readFile(sessionId, path)
        return ToolOutcome.Success(
            jsonObj(
                "path" to file.path,
                "size" to file.size,
                "truncated" to file.truncated,
                "content" to (file.text ?: "[biner ${file.size} byte]"),
            ),
        )
    }

    private fun writeFile(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path")
        val content = args.get("content").asString()
        if (path.isEmpty()) return ToolOutcome.Failure("path wajib diisi", "EARGS")
        val result = engine.writeFile(sessionId, path, content, source = "agent")
        return ToolOutcome.Success(
            jsonObj("path" to result.path, "bytes" to result.bytes, "created" to result.created, "warnings" to toJsonValue(result.warnings)),
        )
    }

    private fun editFile(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path")
        val oldString = args.get("old_string").asString()
        val newString = args.get("new_string").asString()
        if (path.isEmpty()) return ToolOutcome.Failure("path wajib diisi", "EARGS")
        if (oldString.isEmpty()) return ToolOutcome.Failure("old_string wajib diisi", "EARGS")

        val file = engine.readFile(sessionId, path)
        val text = file.text ?: return ToolOutcome.Failure("edit_file hanya untuk file teks", "EBINARY")
        val count = countOccurrences(text, oldString)
        if (count == 0) return ToolOutcome.Failure("old_string tidak ditemukan di $path", "ENOMATCH")
        if (count > 1) return ToolOutcome.Failure("old_string muncul ${count}x di $path; buat lebih spesifik", "EAMBIGUOUS")

        val result = engine.writeFile(sessionId, path, text.replaceFirst(oldString, newString), source = "agent")
        return ToolOutcome.Success(jsonObj("path" to result.path, "replaced" to 1, "bytes" to result.bytes))
    }

    private fun deleteFile(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path")
        if (path.isEmpty()) return ToolOutcome.Failure("path wajib diisi", "EARGS")
        val deleted = engine.deletePath(sessionId, path, source = "agent")
        return ToolOutcome.Success(jsonObj("path" to path, "deleted" to deleted))
    }

    private fun makeDir(args: JsonValue): ToolOutcome {
        val path = args.stringOr("path")
        if (path.isEmpty()) return ToolOutcome.Failure("path wajib diisi", "EARGS")
        engine.mkdir(sessionId, path, source = "agent")
        return ToolOutcome.Success(jsonObj("path" to path, "created" to true))
    }

    private suspend fun exec(args: JsonValue): ToolOutcome {
        val command = args.stringOr("command")
        if (command.isEmpty()) return ToolOutcome.Failure("command wajib diisi", "EARGS")
        val run = executor ?: return ToolOutcome.Failure(
            "Perangkat ini tidak menyediakan shell. Gunakan write_file lalu jalankan lewat panel Terminal.",
            "ENOSHELL",
        )
        val cwd = args.stringOr("cwd", ".")
        val timeout = args.get("timeout_ms").let { if (it is dev.arenalite.core.json.JsonValue.Num) it.value.toLong() else 20000L }
        val absoluteCwd = engine.resolve(sessionId, cwd)
        val result = run.exec(command, absoluteCwd, timeout)
        engine.appendOp(
            sessionId,
            dev.arenalite.core.model.OpLogEntry(
                seq = 0,
                type = "exec",
                path = null,
                source = "agent",
                at = dev.arenalite.core.util.nowIso(),
                detail = mapOf("command" to command, "exitCode" to result.exitCode, "timedOut" to result.timedOut),
            ),
        )
        return ToolOutcome.Success(
            jsonObj(
                "command" to command,
                "cwd" to cwd,
                "exitCode" to result.exitCode,
                "timedOut" to result.timedOut,
                "stdout" to result.stdout.takeLast(MAX_OUTPUT_CHARS),
                "stderr" to result.stderr.takeLast(MAX_OUTPUT_CHARS),
            ),
        )
    }

    private fun workspaceUsage(): ToolOutcome {
        val usage = engine.usage(sessionId)
        return ToolOutcome.Success(usage.toJson())
    }

    private inline fun guard(block: () -> ToolOutcome): ToolOutcome = try {
        block()
    } catch (e: dev.arenalite.core.util.PathEscapeException) {
        ToolOutcome.Failure(e.message ?: "path keluar dari workspace", "EPATHOUTSIDE")
    } catch (e: dev.arenalite.core.util.WorkspaceFileException) {
        ToolOutcome.Failure(e.message ?: "gagal", e.code)
    } catch (e: Exception) {
        ToolOutcome.Failure(e.message ?: e::class.simpleName ?: "error", "EINTERNAL")
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 32_000

        fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                count += 1
                index = haystack.indexOf(needle, index + needle.length)
            }
            return count
        }

        /** The advertised tool surface. Keep in sync with the server's TOOL_SCHEMAS. */
        val SCHEMAS: List<ToolSchema> = listOf(
            ToolSchema("list_files", "List isi sebuah direktori di workspace.", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string")))),
            ToolSchema("read_file", "Baca isi file teks (maks 1 MB).", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("path")))),
            ToolSchema("write_file", "Tulis/timpa file. Direktori dibuat otomatis. Tidak ada batas kuota.", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string"), "content" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("path", "content")))),
            ToolSchema("edit_file", "Ganti satu kemunculan old_string dengan new_string.", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string"), "old_string" to jsonObj("type" to "string"), "new_string" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("path", "old_string", "new_string")))),
            ToolSchema("delete_file", "Hapus file atau direktori.", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("path")))),
            ToolSchema("make_dir", "Buat direktori (rekursif).", jsonObj("type" to "object", "properties" to jsonObj("path" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("path")))),
            ToolSchema("exec", "Jalankan perintah di workspace (bila perangkat menyediakan shell).", jsonObj("type" to "object", "properties" to jsonObj("command" to jsonObj("type" to "string"), "cwd" to jsonObj("type" to "string")), "required" to toJsonValue(listOf("command")))),
            ToolSchema("workspace_usage", "Laporkan pemakaian workspace: jumlah file, total byte, dan batas.", jsonObj("type" to "object", "properties" to jsonObj())),
        )

        /** Convenience for building an args object in tests. */
        fun args(vararg pairs: Pair<String, Any?>): JsonValue = jsonObj(*pairs)

        fun encodeArgs(args: Map<String, Any?>): String = encodeJson(args)
    }
}

/** Small helper so call sites can build `{"a": 1}` without importing the JSON layer. */
fun toolArgs(vararg pairs: Pair<String, Any?>): JsonValue = jsonObj(*pairs)

/** Extract a string field from a tool result payload for UI display. */
fun ToolOutcome.previewText(): String = when (this) {
    is ToolOutcome.Success -> result.toString().take(200)
    is ToolOutcome.Failure -> error
}
