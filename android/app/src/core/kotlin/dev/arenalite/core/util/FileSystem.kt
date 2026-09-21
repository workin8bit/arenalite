package dev.arenalite.core.util

/**
 * Platform-agnostic file system.
 *
 * The Android app backs this with java.io under `context.filesDir`; the JVM test
 * harness backs it with the same java.io on a temp directory. Keeping the core
 * behind this interface is what lets the workspace engine, sync engine and agent
 * loop be unit-tested without an emulator.
 */
interface FileSystem {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): List<DirEntry>
    fun readBytes(path: String): ByteArray
    fun readText(path: String): String = readBytes(path).decodeToString()
    fun writeBytes(path: String, bytes: ByteArray)
    fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())
    fun appendText(path: String, text: String)
    fun mkdirs(path: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
    fun size(path: String): Long
    fun modifiedAt(path: String): Long
    fun join(vararg parts: String): String

    /** Absolute path of the workspace root for a session (used as exec cwd). */
    fun root(): String
}

data class DirEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modifiedAt: Long)

/** Thrown when a path escapes the workspace sandbox. */
class PathEscapeException(val attempted: String) : RuntimeException("Path keluar dari workspace: $attempted")

/** Thrown when the caller asks for a workspace that does not exist. */
class NoSuchWorkspaceException(val sessionId: String) : RuntimeException("Workspace $sessionId tidak ditemukan")

class WorkspaceFileException(message: String, val code: String) : RuntimeException(message)

// ----------------------------------------------------------------- utilities

/** SHA-256 hex, implemented on the JDK's MessageDigest (available on Android too). */
fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

fun randomHex(bytes: Int = 6): String {
    val buf = ByteArray(bytes)
    java.security.SecureRandom().nextBytes(buf)
    return buf.joinToString("") { "%02x".format(it) }
}

fun nowIso(): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date())
}

/** Normalise an untrusted relative path into a POSIX-style workspace path. */
fun normalizeRelative(raw: String): String {
    val parts = raw.replace('\\', '/').split('/')
    val out = mutableListOf<String>()
    for (part in parts) {
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1) else throw PathEscapeException(raw)
            else -> out.add(part)
        }
    }
    return out.joinToString("/")
}

fun isProbablyText(bytes: ByteArray): Boolean {
    val sample = bytes.copyOf(minOf(bytes.size, 512))
    if (sample.isEmpty()) return true
    return sample.none { it == 0.toByte() }
}

// ------------------------------------------------------------- UI formatting

/** Compact byte formatter shared by the Compose UI and the JVM test output. */
fun formatBytesShort(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    val text = if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
    return "$text ${units[index]}"
}

/** "∞" for unlimited caps, otherwise a compact byte count. */
fun formatLimit(limit: Long): String = if (limit == Long.MAX_VALUE) "∞" else formatBytesShort(limit)

fun formatRelativeTime(iso: String): String {
    if (iso.isBlank()) return ""
    val parsed = runCatching {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        format.parse(iso)
    }.getOrNull() ?: return iso
    val seconds = (System.currentTimeMillis() - parsed.time) / 1000
    return when {
        seconds < 60 -> "baru saja"
        seconds < 3600 -> "${seconds / 60} mnt lalu"
        seconds < 86_400 -> "${seconds / 3600} jam lalu"
        seconds < 604_800 -> "${seconds / 86_400} hr lalu"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("id")).format(parsed)
    }
}
