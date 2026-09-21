package dev.arenalite.core.provider

import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.ToolSchema

/** Normalised stream events — every provider adapter maps onto these. */
sealed class ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent()
    data class ToolUse(val id: String, val name: String, val input: JsonValue) : ProviderEvent()
    data class Stop(val reason: String) : ProviderEvent()
    data class Error(val message: String) : ProviderEvent()
}

data class ProviderRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema>,
    val model: String,
    val system: String,
)

interface Provider {
    val id: String

    /** Emit normalised events until the model stops. */
    suspend fun stream(request: ProviderRequest, emit: suspend (ProviderEvent) -> Unit)
}

class ProviderException(message: String) : RuntimeException(message)
