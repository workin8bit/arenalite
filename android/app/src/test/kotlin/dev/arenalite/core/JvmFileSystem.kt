package dev.arenalite.core

import dev.arenalite.core.util.DirEntry
import dev.arenalite.core.util.FileSystem
import java.io.File

/**
 * java.io-backed FileSystem used by the JVM test harness.
 *
 * The Android app ships the same class (see `app/data/AndroidFileSystem.kt`)
 * rooted at `context.filesDir`, which is what lets the core be tested here
 * without an emulator and still run identical logic on a device.
 */
class JvmFileSystem(val rootPath: String) : FileSystem {
    private val rootFile = File(rootPath).apply { mkdirs() }

    override fun root(): String = rootFile.absolutePath

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun list(path: String): List<DirEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).map { f ->
            DirEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0,
                modifiedAt = f.lastModified(),
            )
        }
    }

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun appendText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun delete(path: String) {
        File(path).deleteRecursively()
    }

    override fun rename(from: String, to: String) {
        val target = File(to)
        target.parentFile?.mkdirs()
        File(from).renameTo(target)
    }

    override fun size(path: String): Long = File(path).length()

    override fun modifiedAt(path: String): Long = File(path).lastModified()

    override fun join(vararg parts: String): String {
        val cleaned = parts.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return root()
        var path = File(cleaned.first())
        for (part in cleaned.drop(1)) path = File(path, part)
        return path.absolutePath
    }
}
