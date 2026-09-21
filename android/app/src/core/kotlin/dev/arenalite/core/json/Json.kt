package dev.arenalite.core.json

/**
 * A tiny JSON reader/writer with zero dependencies.
 *
 * The core module has to compile on plain JVM (so it can be unit-tested without
 * the Android SDK) and on Android, so we cannot rely on org.json or Gson being
 * present in both. This is ~200 lines and covers exactly what the agent
 * protocol needs: objects, arrays, strings, numbers, booleans, null.
 */
sealed class JsonValue {
    object Null : JsonValue() {
        override fun toString(): String = "null"
    }

    data class Bool(val value: Boolean) : JsonValue() {
        override fun toString(): String = value.toString()
    }

    data class Num(val value: Double) : JsonValue() {
        override fun toString(): String =
            if (value == value.toLong().toDouble() && !value.isInfinite()) value.toLong().toString() else value.toString()
    }

    data class Str(val value: String) : JsonValue() {
        override fun toString(): String = encodeString(value)
    }

    data class Arr(val items: List<JsonValue>) : JsonValue() {
        override fun toString(): String = items.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue() {
        override fun toString(): String =
            entries.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                "${encodeString(k)}:$v"
            }
    }
}

fun jsonObj(vararg pairs: Pair<String, Any?>): JsonValue.Obj =
    JsonValue.Obj(pairs.associate { (k, v) -> k to toJsonValue(v) })

fun jsonArr(items: List<Any?>): JsonValue.Arr = JsonValue.Arr(items.map { toJsonValue(it) })

fun toJsonValue(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is JsonValue -> value
    is Boolean -> JsonValue.Bool(value)
    is Int -> JsonValue.Num(value.toDouble())
    is Long -> JsonValue.Num(value.toDouble())
    is Double -> JsonValue.Num(value)
    is Float -> JsonValue.Num(value.toDouble())
    is String -> JsonValue.Str(value)
    is Map<*, *> -> JsonValue.Obj(value.entries.associate { (k, v) -> k.toString() to toJsonValue(v) })
    is List<*> -> JsonValue.Arr(value.map { toJsonValue(it) })
    is Array<*> -> JsonValue.Arr(value.map { toJsonValue(it) })
    else -> JsonValue.Str(value.toString())
}

// -------------------------------------------------------------------- helpers

fun JsonValue.obj(): JsonValue.Obj = this as? JsonValue.Obj ?: JsonValue.Obj(emptyMap())
fun JsonValue.arr(): JsonValue.Arr = this as? JsonValue.Arr ?: JsonValue.Arr(emptyList())

fun JsonValue.get(key: String): JsonValue =
    (this as? JsonValue.Obj)?.entries?.get(key) ?: JsonValue.Null

fun JsonValue.stringOrNull(key: String): String? = (get(key) as? JsonValue.Str)?.value
fun JsonValue.stringOr(key: String, fallback: String = ""): String = stringOrNull(key) ?: fallback
fun JsonValue.intOr(key: String, fallback: Int = 0): Int =
    ((get(key) as? JsonValue.Num)?.value?.toInt()) ?: fallback

fun JsonValue.longOr(key: String, fallback: Long = 0L): Long =
    ((get(key) as? JsonValue.Num)?.value?.toLong()) ?: fallback

fun JsonValue.boolOr(key: String, fallback: Boolean = false): Boolean =
    (get(key) as? JsonValue.Bool)?.value ?: fallback

fun JsonValue.listOr(key: String): List<JsonValue> = (get(key) as? JsonValue.Arr)?.items ?: emptyList()
fun JsonValue.objOr(key: String): JsonValue.Obj = get(key).obj()

fun JsonValue.asString(): String = when (this) {
    is JsonValue.Str -> value
    is JsonValue.Null -> ""
    else -> toString()
}

fun JsonValue.asMap(): Map<String, JsonValue> = (this as? JsonValue.Obj)?.entries ?: emptyMap()
fun JsonValue.asList(): List<JsonValue> = (this as? JsonValue.Arr)?.items ?: emptyList()

fun JsonValue.toAny(): Any? = when (this) {
    is JsonValue.Null -> null
    is JsonValue.Bool -> value
    is JsonValue.Num -> if (value == value.toLong().toDouble()) value.toLong() else value
    is JsonValue.Str -> value
    is JsonValue.Arr -> items.map { it.toAny() }
    is JsonValue.Obj -> entries.mapValues { it.value.toAny() }
}

// --------------------------------------------------------------------- parser

class JsonParseException(message: String) : RuntimeException(message)

fun parseJson(text: String): JsonValue {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    if (!parser.atEnd()) throw JsonParseException("Sisa input tak terduga di posisi ${parser.pos}")
    return value
}

private class JsonParser(private val src: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= src.length

    fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos += 1
    }

    fun parseValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) throw JsonParseException("JSON berakhir tak terduga")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't', 'f' -> parseBool()
            'n' -> parseNull()
            else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonParseException("Karakter tak terduga '$c' di $pos")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos += 1
            return JsonValue.Obj(map)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            map[key] = parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                '}' -> return JsonValue.Obj(map)
                else -> throw JsonParseException("Diharapkan ',' atau '}', dapat '$c'")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos += 1
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                ']' -> return JsonValue.Arr(items)
                else -> throw JsonParseException("Diharapkan ',' atau ']', dapat '$c'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw JsonParseException("String tidak ditutup")
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c != '\\') {
                sb.append(c)
                continue
            }
            if (atEnd()) throw JsonParseException("Escape tidak lengkap")
            when (val esc = src[pos++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (pos + 4 > src.length) throw JsonParseException("Unicode escape terpotong")
                    sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                    pos += 4
                }
                else -> throw JsonParseException("Escape tak dikenal '\\$esc'")
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos += 1
        while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos += 1
        val raw = src.substring(start, pos)
        return JsonValue.Num(raw.toDoubleOrNull() ?: throw JsonParseException("Angka tidak valid: $raw"))
    }

    private fun parseBool(): JsonValue.Bool {
        return if (src.startsWith("true", pos)) {
            pos += 4
            JsonValue.Bool(true)
        } else if (src.startsWith("false", pos)) {
            pos += 5
            JsonValue.Bool(false)
        } else {
            throw JsonParseException("Literal boolean tidak valid di $pos")
        }
    }

    private fun parseNull(): JsonValue.Null {
        if (src.startsWith("null", pos)) {
            pos += 4
            return JsonValue.Null
        }
        throw JsonParseException("Literal null tidak valid di $pos")
    }

    private fun peek(): Char = if (atEnd()) throw JsonParseException("Input habis") else src[pos]
    private fun next(): Char = if (atEnd()) throw JsonParseException("Input habis") else src[pos++]
    private fun expect(c: Char) {
        if (atEnd() || src[pos] != c) throw JsonParseException("Diharapkan '$c' di posisi $pos")
        pos += 1
    }
}

// --------------------------------------------------------------------- writer

fun encodeString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun encodeJson(value: Any?): String = toJsonValue(value).toString()

fun prettyJson(value: JsonValue, indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val inner = "  ".repeat(indent + 1)
    return when (value) {
        is JsonValue.Obj ->
            if (value.entries.isEmpty()) "{}"
            else value.entries.entries.joinToString(prefix = "{\n", postfix = "\n$pad}", separator = ",\n") { (k, v) ->
                "$inner${encodeString(k)}: ${prettyJson(v, indent + 1)}"
            }
        is JsonValue.Arr ->
            if (value.items.isEmpty()) "[]"
            else value.items.joinToString(prefix = "[\n", postfix = "\n$pad]", separator = ",\n") { "$inner${prettyJson(it, indent + 1)}" }
        else -> value.toString()
    }
}
