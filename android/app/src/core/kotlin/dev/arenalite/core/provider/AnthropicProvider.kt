package dev.arenalite.core.provider

import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.json.arr
import dev.arenalite.core.json.asMap
import dev.arenalite.core.json.asString
import dev.arenalite.core.json.get
import dev.arenalite.core.json.intOr
import dev.arenalite.core.json.jsonArr
import dev.arenalite.core.json.jsonObj
import dev.arenalite.core.json.listOr
import dev.arenalite.core.json.obj
import dev.arenalite.core.json.parseJson
import dev.arenalite.core.json.stringOr
import dev.arenalite.core.json.stringOrNull
import dev.arenalite.core.json.toJsonValue
import dev.arenalite.core.util.HttpClient
import dev.arenalite.core.util.SseParser

/**
 * Anthropic Messages API over streaming SSE.
 *
 * The server holds the API key (arena-style); the app talks to the operator's
 * gateway. Swap [endpoint] for `https://api.anthropic.com/v1/messages` and put
 * the key in `extraHeaders` to hit Anthropic directly.
 */
class AnthropicProvider(
    private val http: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String? = null,
    private val maxTokens: Int = 4096,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {
    override val id: String = "anthropic"

    override suspend fun stream(request: ProviderRequest, emit: suspend (ProviderEvent) -> Unit) {
        val body = jsonObj(
            "model" to request.model,
            "max_tokens" to maxTokens,
            "stream" to true,
            "system" to request.system,
            "messages" to jsonArr(request.messages.map { messageToJson(it) }),
            "tools" to jsonArr(request.tools.map { it.toJson() }),
        )

        val headers = buildMap {
            put("content-type", "application/json")
            put("anthropic-version", "2023-06-01")
            apiKey?.let { put("x-api-key", it) }
            putAll(extraHeaders)
        }

        val stream = http.postStream(endpoint, headers, body.toString())
        if (stream.status !in 200..299) {
            val text = stream.readChunk() ?: ""
            stream.close()
            throw ProviderException("Anthropic ${stream.status}: ${text.take(400)}")
        }

        val parser = SseParser()
        val partialJson = mutableMapOf<Int, StringBuilder>()
        val toolMeta = mutableMapOf<Int, Pair<String, String>>()
        var stopReason = "end_turn"

        try {
            while (true) {
                val chunk = stream.readChunk() ?: break
                for (event in parser.feed(chunk)) {
                    val payload = runCatching { parseJson(event.data) }.getOrNull() ?: continue
                    when (event.type) {
                        "content_block_start" -> {
                            val block = payload.get("content_block")
                            if (block.stringOr("type") == "tool_use") {
                                val index = payload.intOr("index")
                                partialJson[index] = StringBuilder()
                                toolMeta[index] = block.stringOr("id") to block.stringOr("name")
                            }
                        }
                        "content_block_delta" -> {
                            val delta = payload.get("delta")
                            when (delta.stringOr("type")) {
                                "text_delta" -> emit(ProviderEvent.TextDelta(delta.stringOr("text")))
                                "input_json_delta" -> {
                                    val index = payload.intOr("index")
                                    partialJson.getOrPut(index) { StringBuilder() }.append(delta.stringOr("partial_json"))
                                }
                            }
                        }
                        "message_delta" -> {
                            payload.get("delta").stringOrNull("stop_reason")?.let { stopReason = it }
                        }
                    }
                }
            }
            for (event in parser.flush()) {
                // trailing frame without a blank line; safe to ignore for our purposes
                if (event.type == "message_stop") stopReason = stopReason
            }
        } finally {
            stream.close()
        }

        for ((index, buffer) in partialJson) {
            val (toolId, name) = toolMeta[index] ?: continue
            val input = runCatching { parseJson(buffer.toString()) }.getOrDefault(JsonValue.Obj(emptyMap()))
            emit(ProviderEvent.ToolUse(toolId, name, input))
        }
        emit(ProviderEvent.Stop(stopReason))
    }

    private fun messageToJson(message: dev.arenalite.core.model.ChatMessage): JsonValue = jsonObj(
        "role" to message.role,
        "content" to jsonArr(message.blocks.map { it.toJson() }),
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
    }
}
