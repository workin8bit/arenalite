package dev.arenalite.app.data

import dev.arenalite.core.util.DirEntry
import dev.arenalite.core.util.FileSystem
import java.io.File

/**
 * java.io-backed [FileSystem] rooted at `context.filesDir/workspaces`.
 *
 * This is the on-device twin of the JVM harness's `JvmFileSystem`, which is what
 * makes `tools/run-android-core-tests.sh` a real test of shipping code rather
 * than a look-alike.
 */
class AndroidFileSystem(private val rootDir: File) : FileSystem {

    init {
        rootDir.mkdirs()
    }

    override fun root(): String = rootDir.absolutePath

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun list(path: String): List<DirEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).map { file ->
            DirEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                modifiedAt = file.lastModified(),
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

    /** Free bytes on the device — shown in the UI next to the ∞ quota. */
    fun availableBytes(): Long = rootDir.usableSpace
}
