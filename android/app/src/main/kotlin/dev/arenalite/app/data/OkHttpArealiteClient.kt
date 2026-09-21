package dev.arenalite.app.data

import dev.arenalite.core.util.HttpClient
import dev.arenalite.core.util.HttpResponse
import dev.arenalite.core.util.HttpStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of the core's [HttpClient].
 *
 * Read timeouts are disabled on purpose: an agent turn can run for minutes while
 * the model streams tool calls, and the SSE connection must survive that.
 */
class OkHttpArealiteClient(
    private val client: OkHttpClient = defaultClient(),
) : HttpClient {

    override suspend fun postStream(url: String, headers: Map<String, String>, body: String): HttpStream {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = client.newCall(request).execute()
        return OkHttpStream(response)
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, body?.toRequestBody(JSON))
        }
        client.newCall(builder.build()).execute().use { response ->
            return HttpResponse(
                status = response.code,
                body = response.body?.string() ?: "",
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
            )
        }
    }

    private class OkHttpStream(private val response: okhttp3.Response) : HttpStream {
        private val reader = response.body?.charStream()

        override val status: Int get() = response.code

        override suspend fun readChunk(): String? {
            val stream = reader ?: return null
            val buffer = CharArray(4096)
            val read = runCatching { stream.read(buffer) }.getOrDefault(-1)
            if (read <= 0) return null
            return String(buffer, 0, read)
        }

        override fun close() {
            runCatching { reader?.close() }
            runCatching { response.close() }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // streaming: never time out mid-turn
            .retryOnConnectionFailure(true)
            .build()
    }
}
