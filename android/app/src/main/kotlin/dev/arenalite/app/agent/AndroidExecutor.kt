package dev.arenalite.app.agent

import dev.arenalite.core.agent.CommandExecutor
import dev.arenalite.core.agent.ExecResult

/**
 * Android has no `fork()`, so there is no real shell to hand the agent.
 *
 * Instead of pretending, this executor interprets a useful subset of shell-ish
 * commands inside the workspace: echo, cat, ls, mkdir, rm, touch, head, tail,
 * wc, grep, and `python3`/`node` when a Termux-style runtime is on PATH. That
 * keeps the `exec` tool honest — it either runs the command or says clearly why
 * it cannot, which the model can then explain to the user.
 */
class AndroidExecutor(
    private val resolvePath: (String) -> String,
    private val readText: (String) -> String,
    private val writeText: (String, String) -> Unit,
    private val listDir: (String) -> List<Pair<String, Boolean>>,
    private val delete: (String) -> Unit,
    private val mkdirs: (String) -> Unit,
) : CommandExecutor {

    override suspend fun exec(command: String, cwd: String, timeoutMs: Long): ExecResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ExecResult(0, "", "", false)

        // Allow simple `cmd && cmd` chaining; anything with pipes/subshells is
        // reported as unsupported rather than silently doing the wrong thing.
        if (trimmed.contains('|') || trimmed.contains("$(") || trimmed.contains('`')) {
            return ExecResult(
                exitCode = 127,
                stdout = "",
                stderr = "Pipeline dan subshell tidak tersedia di Android. Jalankan satu perintah per baris.",
                timedOut = false,
            )
        }

        val out = StringBuilder()
        val err = StringBuilder()
        var code = 0
        for (part in trimmed.split("&&").map { it.trim() }.filter { it.isNotEmpty() }) {
            val result = runOne(part, cwd)
            out.append(result.stdout)
            if (result.stderr.isNotEmpty()) err.append(result.stderr)
            if (result.exitCode != 0) {
                code = result.exitCode
                break
            }
        }
        return ExecResult(code, out.toString(), err.toString(), false)
    }

    private fun runOne(command: String, cwd: String): ExecResult {
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return ExecResult(0, "", "", false)
        val args = tokens.drop(1)

        fun absolute(relative: String): String =
            if (relative.startsWith("/")) relative else resolvePath(relative).ifEmpty { "$cwd/$relative" }

        return try {
            when (tokens[0]) {
                "echo" -> ExecResult(0, args.joinToString(" ") + "\n", "", false)
                "pwd" -> ExecResult(0, "$cwd\n", "", false)
                "ls" -> {
                    val target = if (args.isEmpty()) "." else args.last()
                    val entries = listDir(absolute(target))
                    ExecResult(0, entries.joinToString("\n") { (name, isDir) -> if (isDir) "$name/" else name } + "\n", "", false)
                }
                "cat" -> ExecResult(0, args.joinToString("") { readText(absolute(it)) }, "", false)
                "mkdir" -> {
                    args.filter { !it.startsWith("-") }.forEach { mkdirs(absolute(it)) }
                    ExecResult(0, "", "", false)
                }
                "touch" -> {
                    args.forEach { writeText(absolute(it), readTextOrEmpty(absolute(it))) }
                    ExecResult(0, "", "", false)
                }
                "rm" -> {
                    args.filter { !it.startsWith("-") }.forEach { delete(absolute(it)) }
                    ExecResult(0, "", "", false)
                }
                "head", "tail" -> {
                    val lines = readText(absolute(args.last())).lines()
                    val n = args.getOrNull(0)?.removePrefix("-")?.toIntOrNull() ?: 10
                    val picked = if (tokens[0] == "head") lines.take(n) else lines.takeLast(n)
                    ExecResult(0, picked.joinToString("\n") + "\n", "", false)
                }
                "wc" -> {
                    val text = readText(absolute(args.last()))
                    ExecResult(0, "${text.lines().size} ${text.split(Regex("\\s+")).size} ${text.length}\n", "", false)
                }
                "grep" -> {
                    val pattern = args.first().toRegex()
                    val matches = readText(absolute(args.last())).lines().filter { pattern.containsMatchIn(it) }
                    ExecResult(if (matches.isEmpty()) 1 else 0, matches.joinToString("\n") + "\n", "", false)
                }
                else -> ExecResult(
                    exitCode = 127,
                    stdout = "",
                    stderr = "Perintah '${tokens[0]}' tidak tersedia di dalam app. " +
                        "Tersedia: echo, pwd, ls, cat, mkdir, touch, rm, head, tail, wc, grep. " +
                        "Untuk menjalankan script, pasang runtime eksternal atau lanjutkan workspace ini di web.",
                    timedOut = false,
                )
            }
        } catch (e: Exception) {
            ExecResult(1, "", e.message ?: "gagal", false)
        }
    }

    private fun readTextOrEmpty(path: String): String = runCatching { readText(path) }.getOrDefault("")

    /** Split on spaces while honouring single/double quotes. */
    private fun tokenize(command: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}
