package dev.arenalite.core.util

/**
 * Streaming HTTP abstraction.
 *
 * On Android this is implemented with OkHttp; in JVM tests it is a fake that
 * replays canned SSE frames. The core only ever sees [HttpStream], so provider
 * adapters stay testable offline.
 */
interface HttpClient {
    suspend fun postStream(url: String, headers: Map<String, String>, body: String): HttpStream
    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): HttpResponse
}

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val ok: Boolean get() = status in 200..299
}

interface HttpStream {
    val status: Int

    /** Read the next chunk; null when the stream is finished. */
    suspend fun readChunk(): String?

    fun close()
}

class HttpException(val status: Int, message: String) : RuntimeException("HTTP $status: $message")

/**
 * Incremental SSE frame parser: feed it raw text as it arrives, get complete
 * `event:` / `data:` payloads back. Handles frames split across chunk
 * boundaries, which is the usual way naive SSE clients break.
 */
class SseParser {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<SseEvent> {
        buffer.append(chunk)
        val out = mutableListOf<SseEvent>()
        while (true) {
            val index = buffer.indexOf("\n\n")
            if (index < 0) break
            val block = buffer.substring(0, index)
            buffer.delete(0, index + 2)
            val event = parseBlock(block) ?: continue
            out.add(event)
        }
        return out
    }

    fun flush(): List<SseEvent> {
        if (buffer.isBlank()) return emptyList()
        val block = buffer.toString()
        buffer.setLength(0)
        return parseBlock(block)?.let { listOf(it) } ?: emptyList()
    }

    private fun parseBlock(block: String): SseEvent? {
        var event: String? = null
        val data = StringBuilder()
        for (line in block.split('\n')) {
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
            }
        }
        val name = event ?: return null
        if (data.toString() == "[DONE]") return null
        return SseEvent(name, data.toString())
    }
}

data class SseEvent(val type: String, val data: String)
