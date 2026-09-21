package dev.arenalite.core

import dev.arenalite.core.agent.AgentEvent
import dev.arenalite.core.agent.AgentLoop
import dev.arenalite.core.agent.ToolOutcome
import dev.arenalite.core.agent.ToolRegistry
import dev.arenalite.core.agent.toolArgs
import dev.arenalite.core.json.JsonValue
import dev.arenalite.core.json.encodeJson
import dev.arenalite.core.json.parseJson
import dev.arenalite.core.json.prettyJson
import dev.arenalite.core.json.stringOr
import dev.arenalite.core.model.ChatMessage
import dev.arenalite.core.model.QuotaPolicy
import dev.arenalite.core.provider.MockProvider
import dev.arenalite.core.sync.MirrorBackend
import dev.arenalite.core.sync.SyncEngine
import dev.arenalite.core.workspace.WorkspaceEngine
import kotlinx.coroutines.runBlocking

/**
 * Runs the real core logic of the Android app on the JVM.
 *
 * Everything exercised here — workspace engine, tool registry, agent loop,
 * quota policy, sync engine, JSON codec — is the same source the APK compiles
 * (`app/src/core` is a shared sourceSet), so this is not a re-implementation.
 */
object CoreTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val runner = TestRunner()
        var allOk = true

        println("== json ==")
        jsonTests(runner)
        allOk = runner.report("json") && allOk

        println("== quota ==")
        quotaTests(runner)
        allOk = runner.report("quota") && allOk

        println("== workspace engine ==")
        workspaceTests(runner)
        allOk = runner.report("workspace") && allOk

        println("== tools ==")
        toolTests(runner)
        allOk = runner.report("tools") && allOk

        println("== agent loop ==")
        agentTests(runner)
        allOk = runner.report("agent") && allOk

        println("== sync ==")
        syncTests(runner)
        allOk = runner.report("sync") && allOk

        println(if (allOk) "CORE_OK" else "CORE_FAILED")
        if (!allOk) kotlin.system.exitProcess(1)
    }

    // ------------------------------------------------------------------ json

    private fun jsonTests(r: TestRunner) {
        r.test("parse lalu tulis ulang objek bersarang") {
            val parsed = parseJson("""{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}""")
            assertEquals("""{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}""", parsed.toString())
        }
        r.test("escape string dua arah") {
            val text = "baris1\nbaris2 \"kutip\" \u0001 tab\there"
            val encoded = encodeJson(text)
            assertEquals(text, parseJson(encoded).let { (it as JsonValue.Str).value })
        }
        r.test("angka bulat ditulis tanpa desimal") {
            assertEquals("""{"n":42}""", encodeJson(mapOf("n" to 42)))
        }
        r.test("pretty json bisa dibaca ulang") {
            val value = parseJson("""{"x":{"y":[1,2]}}""")
            assertEquals(value.toString(), parseJson(prettyJson(value)).toString())
        }
        r.test("input rusak melempar exception, bukan crash") {
            assertThrows<Exception>("json tidak lengkap") { parseJson("""{"a":""") }
        }
    }

    // ----------------------------------------------------------------- quota

    private fun quotaTests(r: TestRunner) {
        r.test("paket default unlimited dan tidak menegakkan kuota") {
            val policy = QuotaPolicy.UNLIMITED
            assertEquals(false, policy.enforce)
            assertEquals(Long.MAX_VALUE, policy.maxBytesPerWorkspace)
        }
        r.test("tulis 500 GB ke workspace 900 GB tetap diizinkan") {
            val gb = 1024L * 1024 * 1024
            val decision = QuotaPolicy.UNLIMITED.evaluate(currentBytes = 900 * gb, incomingBytes = 500 * gb)
            assertEquals(true, decision.ok, "unlimited tidak boleh menolak tulis")
            assertTrue(decision.warnings.isNotEmpty(), "harus ada peringatan advisory")
        }
        r.test("keputusan quota masuk ke hasil tulis") {
            val root = tempRoot("arealite-quota-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession("Uji kuota")
                val result = engine.writeFile(session.id, "besar.txt", "x".repeat(2048))
                assertEquals(2048L, result.bytes)
                assertEquals(false, engine.usage(session.id).policy.enforce)
            } finally {
                cleanup(root)
            }
        }
    }

    // ------------------------------------------------------------- workspace

    private fun workspaceTests(r: TestRunner) {
        r.test("buat, tulis, baca file") {
            val root = tempRoot("arealite-ws-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession("Proyek uji")
                assertTrue(session.id.startsWith("ws_"), "id harus berawalan ws_: ${session.id}")
                engine.writeFile(session.id, "src/App.kt", "fun main() {}\n")
                val file = engine.readFile(session.id, "src/App.kt")
                assertEquals("fun main() {}\n", file.text)
                assertEquals(64, file.hash.length)
            } finally {
                cleanup(root)
            }
        }

        r.test("file 5 MB lolos tanpa kuota") {
            val root = tempRoot("arealite-big-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                val result = engine.writeFile(session.id, "big/data.bin", "y".repeat(5 * 1024 * 1024))
                assertEquals(5L * 1024 * 1024, result.bytes)
                assertTrue(engine.usage(session.id).totalBytes >= 5L * 1024 * 1024, "usage harus menghitung file besar")
            } finally {
                cleanup(root)
            }
        }

        r.test("path traversal ditolak") {
            val root = tempRoot("arealite-trav-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                assertThrows<Exception>("traversal harus ditolak") { engine.resolve(session.id, "../../etc/passwd") }
            } finally {
                cleanup(root)
            }
        }

        r.test("oplog mencatat tiap mutasi dengan seq naik") {
            val root = tempRoot("arealite-oplog-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                engine.writeFile(session.id, "a.txt", "1")
                engine.writeFile(session.id, "a.txt", "22")
                engine.deletePath(session.id, "a.txt")

                val page = engine.opsSince(session.id, 0)
                val mine = page.ops.filter { it.path == "a.txt" }
                assertEquals(listOf("file.write", "file.write", "file.delete"), mine.map { it.type })
                val seqs = page.ops.map { it.seq }
                assertEquals(seqs.sorted(), seqs, "seq harus monoton")
                assertEquals(page.cursor, page.ops.last().seq)
                assertEquals(0, engine.opsSince(session.id, page.cursor).ops.size, "cursor harus menyaring op lama")
            } finally {
                cleanup(root)
            }
        }

        r.test("usage menghitung file, direktori, byte") {
            val root = tempRoot("arealite-usage-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                engine.writeFile(session.id, "a.txt", "aaaa")
                engine.writeFile(session.id, "nested/b.txt", "bb")
                val usage = engine.usage(session.id)
                assertEquals(3, usage.files, "a.txt + nested/b.txt + README.md")
                assertEquals(1, usage.directories)
            } finally {
                cleanup(root)
            }
        }

        r.test("fork menghasilkan salinan independen") {
            val root = tempRoot("arealite-fork-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession("Asli")
                engine.writeFile(session.id, "keep.txt", "isi")
                val copy = engine.forkSession(session.id)
                assertEquals("isi", engine.readFile(copy.id, "keep.txt").text)
                engine.writeFile(copy.id, "keep.txt", "ubah")
                assertEquals("isi", engine.readFile(session.id, "keep.txt").text, "fork tidak boleh alias")
            } finally {
                cleanup(root)
            }
        }

        r.test("100 workspace bisa hidup bersamaan") {
            val root = tempRoot("arealite-many-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                repeat(100) { engine.createSession("ws-$it") }
                assertEquals(100, engine.listSessions().size)
                assertEquals(100, engine.globalUsage().workspaces)
            } finally {
                cleanup(root)
            }
        }

        r.test("manifest memakai path relatif + hash") {
            val root = tempRoot("arealite-manifest-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                engine.writeFile(session.id, "dir/one.txt", "satu")
                val manifest = engine.manifest(session.id)
                assertTrue(manifest.files.any { it.path == "dir/one.txt" }, "path harus relatif: ${manifest.files.map { it.path }}")
                assertTrue(manifest.files.all { it.hash.length == 64 }, "hash harus sha256")
            } finally {
                cleanup(root)
            }
        }
    }

    // ----------------------------------------------------------------- tools

    private fun toolTests(r: TestRunner) = runBlocking {
        val root = tempRoot("arealite-tools-")
        val engine = WorkspaceEngine(JvmFileSystem(root))
        val session = engine.createSession()
        val registry = ToolRegistry(engine, session.id, RealCommandExecutor())

        r.suspendTest("setiap schema punya handler") {
            for (schema in ToolRegistry.SCHEMAS) {
                assertTrue(registry.handlers.containsKey(schema.name), "tool ${schema.name} belum ada")
            }
            assertTrue(ToolRegistry.SCHEMAS.size >= 8, "tool surface terlalu kecil")
        }

        r.suspendTest("write_file lalu read_file") {
            val written = registry.call("write_file", toolArgs("path" to "notes.md", "content" to "# halo"))
            assertTrue(written.ok, "write_file gagal: $written")
            val read = registry.call("read_file", toolArgs("path" to "notes.md")) as ToolOutcome.Success
            assertContains(read.result.toString(), "# halo")
        }

        r.suspendTest("edit_file mengganti satu kecocokan unik") {
            registry.call("write_file", toolArgs("path" to "app.py", "content" to "x = 1\ny = 2\n"))
            val edited = registry.call("edit_file", toolArgs("path" to "app.py", "old_string" to "x = 1", "new_string" to "x = 42"))
            assertTrue(edited.ok, "edit_file gagal: $edited")
            val read = registry.call("read_file", toolArgs("path" to "app.py")) as ToolOutcome.Success
            assertContains(read.result.toString(), "x = 42")
        }

        r.suspendTest("edit_file menolak kecocokan ambigu") {
            registry.call("write_file", toolArgs("path" to "dup.txt", "content" to "a\na\n"))
            val ambiguous = registry.call("edit_file", toolArgs("path" to "dup.txt", "old_string" to "a", "new_string" to "b"))
            assertTrue(ambiguous is ToolOutcome.Failure, "harus gagal")
            assertContains((ambiguous as ToolOutcome.Failure).error, "muncul 2x")
        }

        r.suspendTest("exec menjalankan script yang baru ditulis") {
            registry.call("write_file", toolArgs("path" to "sum.py", "content" to "print(sum(range(11)))\n"))
            val outcome = registry.call("exec", toolArgs("command" to "python3 sum.py")) as ToolOutcome.Success
            assertContains(outcome.result.toString(), "\"exitCode\":0")
            assertContains(outcome.result.toString(), "55")
        }

        r.suspendTest("percobaan traversal jadi error tool, bukan crash") {
            val outcome = registry.call("write_file", toolArgs("path" to "../../pwned.txt", "content" to "x"))
            assertTrue(outcome is ToolOutcome.Failure, "harus gagal")
            assertEquals("EPATHOUTSIDE", (outcome as ToolOutcome.Failure).code)
        }

        r.suspendTest("tanpa executor, exec menolak dengan jelas") {
            val noShell = ToolRegistry(engine, session.id, executor = null)
            val outcome = noShell.call("exec", toolArgs("command" to "ls"))
            assertTrue(outcome is ToolOutcome.Failure)
            assertEquals("ENOSHELL", (outcome as ToolOutcome.Failure).code)
        }

        cleanup(root)
    }

    // ------------------------------------------------------------ agent loop

    private fun agentTests(r: TestRunner) = runBlocking {
        r.suspendTest("agent menulis file, menjalankannya, lalu melapor") {
            val root = tempRoot("arealite-agent-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                val registry = ToolRegistry(engine, session.id, RealCommandExecutor())
                val loop = AgentLoop(MockProvider(), registry)

                val events = mutableListOf<AgentEvent>()
                val result = loop.run(listOf(ChatMessage.user("buatkan fibonacci lalu jalankan")), model = "mock-agent") { events.add(it) }

                val tools = events.filterIsInstance<AgentEvent.ToolEnd>()
                assertEquals(listOf("write_file", "exec"), tools.map { it.name })
                assertTrue(tools.all { it.ok }, "semua tool harus sukses")
                assertContains(tools.last().detail, "0, 1, 1, 2, 3, 5, 8")
                assertEquals("end_turn", result.stopReason)
                assertEquals(2, result.turns)
                assertContains(engine.readFile(session.id, "fib.py").text ?: "", "def fib")
            } finally {
                cleanup(root)
            }
        }

        r.suspendTest("error tool dikembalikan ke model dan loop tetap berhenti") {
            val root = tempRoot("arealite-agent2-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                val registry = ToolRegistry(engine, session.id, RealCommandExecutor())
                val loop = AgentLoop(MockProvider(), registry)
                // sabotage write_file
                val broken = object : ToolRegistry(engine, session.id, RealCommandExecutor()) {
                    override suspend fun call(name: String, args: JsonValue): ToolOutcome =
                        if (name == "write_file") ToolOutcome.Failure("disk penuh (simulasi)", "EIO") else super.call(name, args)
                }
                val result = loop.run(listOf(ChatMessage.user("buatkan fibonacci")), model = "mock-agent")
                assertEquals("end_turn", result.stopReason)
                assertTrue(broken.handlers.isNotEmpty(), "registry tetap utuh")
            } finally {
                cleanup(root)
            }
        }

        r.suspendTest("pembatalan menghentikan loop sebelum turn pertama") {
            val root = tempRoot("arealite-agent3-")
            try {
                val engine = WorkspaceEngine(JvmFileSystem(root))
                val session = engine.createSession()
                val loop = AgentLoop(MockProvider(), ToolRegistry(engine, session.id))
                val result = loop.run(listOf(ChatMessage.user("buatkan fibonacci")), model = "mock-agent", isCancelled = { true })
                assertEquals("aborted", result.stopReason)
                assertEquals(0, result.turns)
            } finally {
                cleanup(root)
            }
        }
    }

    // ------------------------------------------------------------------ sync

    private fun syncTests(r: TestRunner) = runBlocking {
        r.suspendTest("push hanya mengunggah file yang berubah") {
            val root = tempRoot("arealite-sync-")
            try {
                val fs = JvmFileSystem(root)
                val engine = WorkspaceEngine(fs)
                val session = engine.createSession()
                val backend = MirrorBackend(fs, "$root/remote")
                val sync = SyncEngine(engine, backend) { path -> java.io.File(path).readBytes() }

                engine.writeFile(session.id, "a.txt", "satu")
                val first = sync.push(session.id)
                assertTrue(first.uploaded >= 2, "README.md + a.txt harus terunggah, dapat ${first.uploaded}")

                val progress = mutableListOf<String>()
                sync.push(session.id) { path, _ -> progress.add(path) }
                assertEquals(0, progress.size, "push kedua tidak boleh mengunggah ulang")

                engine.writeFile(session.id, "a.txt", "satu (ubah)")
                val third = mutableListOf<String>()
                sync.push(session.id) { path, _ -> third.add(path) }
                assertEquals(listOf("a.txt"), third)
            } finally {
                cleanup(root)
            }
        }

        r.suspendTest("pull memulihkan workspace ke engine lain") {
            val rootA = tempRoot("arealite-syncA-")
            val rootB = tempRoot("arealite-syncB-")
            try {
                val fsA = JvmFileSystem(rootA)
                val engineA = WorkspaceEngine(fsA)
                val sessionA = engineA.createSession("Sumber")
                engineA.writeFile(sessionA.id, "src/main.kt", "fun main() {}\n")
                val backend = MirrorBackend()
                SyncEngine(engineA, backend) { path -> java.io.File(path).readBytes() }.push(sessionA.id)

                val fsB = JvmFileSystem(rootB)
                val engineB = WorkspaceEngine(fsB)
                val sessionB = engineB.createSession("Tujuan")
                val syncB = SyncEngine(engineB, backend) { path -> java.io.File(path).readBytes() }
                syncB.bindRemote(sessionB.id, sessionA.id)
                val report = syncB.pull(sessionB.id)

                assertTrue(report.downloaded >= 2, "harus mengunduh file, dapat ${report.downloaded}")
                assertEquals("fun main() {}\n", engineB.readFile(sessionB.id, "src/main.kt").text)
            } finally {
                cleanup(rootA)
                cleanup(rootB)
            }
        }

        r.suspendTest("status melaporkan file yang menunggu upload") {
            val root = tempRoot("arealite-syncstatus-")
            try {
                val fs = JvmFileSystem(root)
                val engine = WorkspaceEngine(fs)
                val session = engine.createSession()
                val sync = SyncEngine(engine, MirrorBackend()) { path -> java.io.File(path).readBytes() }
                engine.writeFile(session.id, "a.txt", "a")
                sync.push(session.id)
                engine.writeFile(session.id, "b.txt", "b")
                val status = sync.status(session.id)
                assertEquals(1, status.pendingUpload)
                assertEquals(status.remoteFiles, status.localFiles - 1)
            } finally {
                cleanup(root)
            }
        }
    }
}
