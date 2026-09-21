package dev.arenalite.core.provider

import dev.arenalite.core.json.get
import dev.arenalite.core.json.jsonObj
import dev.arenalite.core.json.stringOr
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.ContentBlock
import dev.arenalite.core.json.toJsonValue

/**
 * Offline provider used for on-device demos, UI previews and unit tests.
 *
 * It walks the same event sequence as a real provider — text deltas, tool_use,
 * tool_result round trip, final stop — so the whole agent loop and the Compose
 * UI can be exercised with no network and no API key.
 */
class MockProvider(private val delayMs: Long = 0L) : Provider {
    override val id: String = "mock"

    override suspend fun stream(request: ProviderRequest, emit: suspend (ProviderEvent) -> Unit) {
        val lastUserText = request.messages.lastOrNull { it.role == "user" && it.text.isNotBlank() }?.text ?: ""
        val hadToolResult = request.messages.any { message ->
            message.blocks.any { it is ContentBlock.ToolResult }
        }
        val toolNames = request.tools.map { it.name }
        val intent = detectIntent(lastUserText)
        val plan = planFor(intent, lastUserText)

        if (!hadToolResult && plan.calls.isNotEmpty()) {
            plan.preamble.takeIf { it.isNotEmpty() }?.let { emitText(it, emit) }
            for (call in plan.calls) {
                if (toolNames.isEmpty() || toolNames.contains(call.first)) {
                    emit(ProviderEvent.ToolUse("toolu_mock_${call.first}_${counter++}", call.first, call.second))
                }
            }
            emit(ProviderEvent.Stop("tool_use"))
            return
        }

        val results = request.messages.flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }
        emitText(plan.closing(results), emit)
        emit(ProviderEvent.Stop("end_turn"))
    }

    private suspend fun emitText(text: String, emit: suspend (ProviderEvent) -> Unit) {
        var index = 0
        while (index < text.length) {
            val end = minOf(text.length, index + 6)
            emit(ProviderEvent.TextDelta(text.substring(index, end)))
            index = end
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        }
    }

    private var counter = 0

    companion object {
        fun detectIntent(text: String): String {
            val t = text.lowercase()
            return when {
                "fibonacci" in t -> "fibonacci"
                listOf("hapus", "delete", "remove").any { it in t } -> "delete"
                listOf("daftar file", "list", "isi workspace").any { it in t } -> "list"
                listOf("usage", "kuota", "penyimpanan", "storage").any { it in t } -> "usage"
                listOf("jalankan", "run", "eksekusi").any { it in t } -> "exec"
                listOf("file", "buat", "script", "kode").any { it in t } -> "write"
                else -> "chat"
            }
        }

        private data class Plan(
            val preamble: String,
            val calls: List<Pair<String, dev.arenalite.core.json.JsonValue>>,
            val closing: (List<ContentBlock.ToolResult>) -> String,
        )

        private fun planFor(intent: String, userText: String): Plan = when (intent) {
            "fibonacci" -> Plan(
                preamble = "Saya buatkan script fibonacci di workspace, lalu langsung saya jalankan.",
                calls = listOf(
                    "write_file" to jsonObj("path" to "fib.py", "content" to FIBONACCI_SOURCE),
                    "exec" to jsonObj("command" to "python3 fib.py 15"),
                ),
                closing = { results -> "Selesai. `fib.py` tersimpan di workspace dan outputnya: `${lastOutput(results)}`." },
            )
            "write" -> {
                val name = Regex("""(\S+\.(py|js|ts|json|md|txt|sh))""").find(userText)?.value ?: "catatan.md"
                Plan(
                    preamble = "Saya tulis `$name` ke workspace.",
                    calls = listOf("write_file" to jsonObj("path" to name, "content" to "# $name\n\nDibuat oleh agent Arealite.\n")),
                    closing = { "`$name` sudah tersimpan. Tidak ada batas kuota, jadi lanjutkan saja." },
                )
            }
            "list" -> Plan(
                preamble = "Saya cek isi workspace.",
                calls = listOf("list_files" to jsonObj("path" to ".")),
                closing = { results -> "Isi workspace:\n${formatEntries(results)}" },
            )
            "usage" -> Plan(
                preamble = "Saya hitung pemakaian workspace.",
                calls = listOf("workspace_usage" to jsonObj()),
                closing = { results -> "Pemakaian: ${formatUsage(results)}. Batas kuota: ∞ (unlimited)." },
            )
            "delete" -> {
                val name = Regex("""(\S+\.\w+)""").find(userText)?.value
                Plan(
                    preamble = if (name != null) "Saya hapus `$name`." else "Sebutkan nama file yang mau dihapus.",
                    calls = if (name != null) listOf("delete_file" to jsonObj("path" to name)) else emptyList(),
                    closing = { if (name != null) "`$name` dihapus dari workspace." else "Tidak ada yang dihapus." },
                )
            }
            "exec" -> {
                val cmd = Regex("""`([^`]+)`""").find(userText)?.groupValues?.get(1) ?: "echo halo dari workspace"
                Plan(
                    preamble = "Menjalankan perintah di workspace.",
                    calls = listOf("exec" to jsonObj("command" to cmd)),
                    closing = { results -> "Output:\n```\n${lastOutput(results)}\n```" },
                )
            }
            else -> Plan(
                preamble = "",
                calls = emptyList(),
                closing = { "Saya agent Arealite. Workspace ini unlimited — minta saya menulis file, menjalankan perintah, atau menyusun proyek apa pun." },
            )
        }

        private fun lastOutput(results: List<ContentBlock.ToolResult>): String {
            for (result in results.asReversed()) {
                val payload = runCatching { dev.arenalite.core.json.parseJson(result.content) }.getOrNull() ?: continue
                payload.stringOr("stdout").takeIf { it.isNotBlank() }?.let { return it.trim() }
                payload.stringOr("content").takeIf { it.isNotBlank() }?.let { return it.trim() }
            }
            return "(tidak ada output)"
        }

        private fun formatEntries(results: List<ContentBlock.ToolResult>): String {
            for (result in results.asReversed()) {
                val payload = runCatching { dev.arenalite.core.json.parseJson(result.content) }.getOrNull() ?: continue
                val entries = payload.get("entries") as? dev.arenalite.core.json.JsonValue.Arr ?: continue
                return entries.items.joinToString("\n") { entry ->
                    val type = if (entry.stringOr("type") == "dir") "📁" else "📄"
                    "- $type `${entry.stringOr("name")}` (${entry.get("size")} byte)"
                }
            }
            return "(kosong)"
        }

        private fun formatUsage(results: List<ContentBlock.ToolResult>): String {
            for (result in results.asReversed()) {
                val payload = runCatching { dev.arenalite.core.json.parseJson(result.content) }.getOrNull() ?: continue
                val files = payload.get("files")
                if (files is dev.arenalite.core.json.JsonValue.Num) {
                    val bytes = payload.get("totalBytes")
                    val dirs = payload.get("directories")
                    return "${files.value.toInt()} file, ${dirs} direktori, ${bytes} byte"
                }
            }
            return "(tidak diketahui)"
        }

        private val FIBONACCI_SOURCE = """
            |import sys
            |
            |def fib(n):
            |    a, b = 0, 1
            |    out = []
            |    for _ in range(n):
            |        out.append(a)
            |        a, b = b, a + b
            |    return out
            |
            |if __name__ == "__main__":
            |    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
            |    print(", ".join(map(str, fib(n))))
        """.trimMargin()
    }
}
