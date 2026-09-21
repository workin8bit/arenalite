package dev.arenalite.core

import dev.arenalite.core.agent.CommandExecutor
import dev.arenalite.core.agent.ExecResult
import dev.arenalite.core.util.HttpClient
import dev.arenalite.core.util.HttpResponse
import dev.arenalite.core.util.HttpStream
import java.io.File
import java.nio.file.Files

/** Minimal assertion helpers so the core suite needs no test framework jar. */
class TestFailure(message: String) : AssertionError(message)

fun assertTrue(condition: Boolean, message: String = "kondisi harus true") {
    if (!condition) throw TestFailure(message)
}

fun assertFalse(condition: Boolean, message: String) = assertTrue(!condition, message)

fun <T> assertEquals(expected: T, actual: T, message: String = "") {
    if (expected != actual) throw TestFailure("$message\n  diharapkan: $expected\n  aktual    : $actual")
}

fun assertContains(haystack: String, needle: String, message: String = "") {
    if (!haystack.contains(needle)) throw TestFailure("$message\n  '$needle' tidak ditemukan di:\n$haystack")
}

inline fun <reified T : Throwable> assertThrows(message: String = "", block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw TestFailure("$message: diharapkan ${T::class.simpleName}, dapat ${e::class.simpleName}: ${e.message}")
    }
    throw TestFailure("$message: diharapkan ${T::class.simpleName}, tidak ada exception")
}

fun tempRoot(prefix: String = "arealite-core-"): String {
    val dir = Files.createTempDirectory(prefix).toFile()
    dir.deleteOnExit()
    return dir.absolutePath
}

/** Deletes a tree; used to keep the temp dirs from piling up. */
fun cleanup(root: String) {
    File(root).deleteRecursively()
}

/** Runs one named test and reports the outcome. */
class TestRunner {
    private var passed = 0
    private val failures = mutableListOf<Pair<String, String>>()

    fun test(name: String, block: () -> Unit) {
        try {
            block()
            passed += 1
            println("  ok   $name")
        } catch (e: Throwable) {
            failures.add(name to (e.message ?: e::class.simpleName ?: "error"))
            println("  FAIL $name -> ${e.message}")
        }
    }

    suspend fun suspendTest(name: String, block: suspend () -> Unit) {
        try {
            block()
            passed += 1
            println("  ok   $name")
        } catch (e: Throwable) {
            failures.add(name to (e.message ?: e::class.simpleName ?: "error"))
            println("  FAIL $name -> ${e.message}")
        }
    }

    fun report(suite: String): Boolean {
        val total = passed + failures.size
        println("[$suite] lulus $passed/$total")
        for ((name, message) in failures) println("  ✗ $name: ${message.lineSequence().first()}")
        return failures.isEmpty()
    }

    val failureCount: Int get() = failures.size
}

/** CommandExecutor that shells out for real — proves exec works end to end. */
class RealCommandExecutor : CommandExecutor {
    override suspend fun exec(command: String, cwd: String, timeoutMs: Long): ExecResult {
        val process = ProcessBuilder("/bin/bash", "-lc", command)
            .directory(File(cwd))
            .redirectErrorStream(false)
            .start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ExecResult(-1, "", "waktu habis", true)
        }
        return ExecResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
            timedOut = false,
        )
    }
}

/** HttpClient that replays canned SSE frames — no network needed. */
class FakeHttpClient(private val frames: List<String>, val status: Int = 200) : HttpClient {
    var lastBody: String? = null
    var lastUrl: String? = null
    var lastHeaders: Map<String, String> = emptyMap()

    override suspend fun postStream(url: String, headers: Map<String, String>, body: String): HttpStream {
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        return FakeStream(status, frames)
    }

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        HttpResponse(status, frames.joinToString(""), headers)
}

private class FakeStream(override val status: Int, private val frames: List<String>) : HttpStream {
    private var index = 0
    override suspend fun readChunk(): String? = if (index < frames.size) frames[index++] else null
    override fun close() {}
}
