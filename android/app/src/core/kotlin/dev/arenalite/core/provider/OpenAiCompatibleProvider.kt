package dev.arenalite.core.provider

import dev.arenalite.core.json.JsonValue
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
import dev.arenalite.core.util.HttpClient
import dev.arenalite.core.util.SseParser

/**
 * OpenAI-compatible chat/completions streaming. The same class serves
 * api.openai.com, OpenRouter and any local OpenAI-shaped gateway, so a
 * self-hosted operator can point the app at their own inference box.
 */
class OpenAiCompatibleProvider(
    private val http: HttpClient,
    override val id: String = "openai",
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val apiKey: String? = null,
    private val maxTokens: Int = 4096,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override suspend fun stream(request: ProviderRequest, emit: suspend (ProviderEvent) -> Unit) {
        val messages = buildList {
            if (request.system.isNotBlank()) add(jsonObj("role" to "system", "content" to request.system))
            for (message in request.messages) add(jsonObj("role" to message.role, "content" to jsonArr(message.blocks.map { it.toJson() })))
        }

        val body = jsonObj(
            "model" to request.model,
            "stream" to true,
            "max_tokens" to maxTokens,
            "messages" to jsonArr(messages),
            "tools" to jsonArr(
                request.tools.map {
                    jsonObj(
                        "type" to "function",
                        "function" to jsonObj("name" to it.name, "description" to it.description, "parameters" to it.inputSchema),
                    )
                },
            ),
        )

        val headers = buildMap {
            put("content-type", "application/json")
            apiKey?.let { put("authorization", "Bearer $it") }
            putAll(extraHeaders)
        }

        val stream = http.postStream(endpoint, headers, body.toString())
        if (stream.status !in 200..299) {
            val text = stream.readChunk() ?: ""
            stream.close()
            throw ProviderException("$id ${stream.status}: ${text.take(400)}")
        }

        val parser = SseParser()
        val pending = mutableMapOf<Int, PendingCall>()
        var stopReason = "end_turn"

        try {
            while (true) {
                val chunk = stream.readChunk() ?: break
                for (event in parser.feed(chunk)) {
                    val payload = runCatching { parseJson(event.data) }.getOrNull() ?: continue
                    val choices = payload.listOr("choices")
                    val choice = choices.firstOrNull() ?: continue
                    val delta = choice.get("delta")

                    delta.stringOrNull("content")?.let { if (it.isNotEmpty()) emit(ProviderEvent.TextDelta(it)) }

                    for (call in delta.listOr("tool_calls")) {
                        val index = call.intOr("index")
                        val slot = pending.getOrPut(index) { PendingCall() }
                        call.stringOrNull("id")?.let { slot.id = it }
                        val fn = call.get("function")
                        fn.stringOrNull("name")?.let { slot.name = it }
                        slot.arguments.append(fn.stringOr("arguments"))
                    }

                    choice.stringOrNull("finish_reason")?.let {
                        stopReason = if (it == "tool_calls") "tool_use" else it
                    }
                }
            }
        } finally {
            stream.close()
        }

        for (call in pending.values) {
            val name = call.name ?: continue
            val input = runCatching { parseJson(call.arguments.toString()) }.getOrDefault(JsonValue.Obj(emptyMap()))
            emit(ProviderEvent.ToolUse(call.id ?: "call_${name.hashCode()}", name, input))
        }
        emit(ProviderEvent.Stop(stopReason))
    }

    private class PendingCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
