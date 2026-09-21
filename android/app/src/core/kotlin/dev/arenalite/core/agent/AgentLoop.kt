package dev.arenalite.core.agent

import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.ContentBlock
import dev.arenalite.core.model.ToolSchema
import dev.arenalite.core.provider.Provider
import dev.arenalite.core.provider.ProviderEvent
import dev.arenalite.core.provider.ProviderRequest
import dev.arenalite.core.json.toAny

/** Events the UI subscribes to while a turn is running. */
sealed class AgentEvent {
    data class TurnStart(val turn: Int) : AgentEvent()
    data class TextDelta(val text: String) : AgentEvent()
    data class ToolStart(val id: String, val name: String, val input: JsonValue) : AgentEvent()
    data class ToolEnd(val id: String, val name: String, val ok: Boolean, val detail: String) : AgentEvent()
    data class MessagePersisted(val message: ChatMessage) : AgentEvent()
    data class Done(val stopReason: String, val turns: Int) : AgentEvent()
}

data class AgentTurnResult(val history: List<ChatMessage>, val stopReason: String, val turns: Int)

/**
 * The agent loop, shared in spirit with the server implementation:
 * send messages → stream deltas → execute tool_use blocks → append tool_result
 * blocks → repeat until the model stops asking for tools.
 */
class AgentLoop(
    private val provider: Provider,
    private val tools: ToolRegistry,
    private val schemas: List<ToolSchema> = ToolRegistry.SCHEMAS,
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val systemPrompt: String = SYSTEM_PROMPT,
) {
    suspend fun run(
        initialMessages: List<ChatMessage>,
        model: String,
        isCancelled: () -> Boolean = { false },
        emit: suspend (AgentEvent) -> Unit = {},
    ): AgentTurnResult {
        val history = initialMessages.toMutableList()
        var stopReason = "end_turn"
        var turn = 0

        while (turn < maxTurns) {
            if (isCancelled()) {
                stopReason = "aborted"
                break
            }
            turn += 1
            emit(AgentEvent.TurnStart(turn))

            val blocks = mutableListOf<ContentBlock>()
            val pendingCalls = mutableListOf<ProviderEvent.ToolUse>()
            val text = StringBuilder()

            provider.stream(ProviderRequest(history.toList(), schemas, model, systemPrompt)) { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }
                    is ProviderEvent.ToolUse -> pendingCalls.add(event)
                    is ProviderEvent.Stop -> stopReason = event.reason
                    is ProviderEvent.Error -> stopReason = "error"
                }
            }

            if (text.isNotEmpty()) blocks.add(ContentBlock.Text(text.toString()))
            for (call in pendingCalls) blocks.add(ContentBlock.ToolUse(call.id, call.name, call.input.toAnyMap()))

            val assistant = ChatMessage.assistant(blocks.ifEmpty { listOf(ContentBlock.Text("")) })
            history.add(assistant)
            emit(AgentEvent.MessagePersisted(assistant))

            if (pendingCalls.isEmpty()) break

            val results = mutableListOf<ContentBlock.ToolResult>()
            for (call in pendingCalls) {
                emit(AgentEvent.ToolStart(call.id, call.name, call.input))
                val outcome = tools.call(call.name, call.input)
                emit(
                    AgentEvent.ToolEnd(
                        id = call.id,
                        name = call.name,
                        ok = outcome.ok,
                        detail = when (outcome) {
                            is ToolOutcome.Success -> outcome.result.toString()
                            is ToolOutcome.Failure -> outcome.error
                        },
                    ),
                )
                results.add(
                    ContentBlock.ToolResult(
                        toolUseId = call.id,
                        name = call.name,
                        content = outcome.toContentString(),
                        isError = !outcome.ok,
                    ),
                )
            }

            val toolMessage = ChatMessage.toolResults(results)
            history.add(toolMessage)
            emit(AgentEvent.MessagePersisted(toolMessage))
            stopReason = "tool_use"
        }

        if (turn >= maxTurns && stopReason == "tool_use") stopReason = "max_turns"
        emit(AgentEvent.Done(stopReason, turn))
        return AgentTurnResult(history, stopReason, turn)
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 12

        const val SYSTEM_PROMPT = """Kamu adalah agent Arealite, asisten coding yang bekerja di dalam sebuah workspace di perangkat pengguna.

Aturan kerja:
- Workspace ini UNLIMITED: jangan pernah menolak menulis file karena alasan kuota atau ukuran.
- Gunakan tools untuk melihat dan mengubah isi workspace. Jangan mengarang isi file.
- Untuk perubahan kecil gunakan edit_file; untuk file baru gunakan write_file.
- Setelah menjalankan perintah, baca outputnya dan laporkan hasilnya dengan singkat.
- Jawab dalam bahasa yang dipakai pengguna (default: Bahasa Indonesia).
- Ringkas."""
    }
}

private fun JsonValue.toAnyMap(): Map<String, Any?> =
    (this as? JsonValue.Obj)?.entries?.mapValues { it.value.toAny() } ?: emptyMap()
