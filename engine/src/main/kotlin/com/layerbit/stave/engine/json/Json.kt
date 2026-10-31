package com.layerbit.stave.engine.json

/**
 * A small JSON reader and writer, so the engine - which has no org.json - can hold the project
 * file's shape and be executed on the JVM. Hand-written access by name, never reflection, so
 * the shrinker has nothing to keep and a renamed field is a compile error, not a silent null.
 *
 * Two rules every float obeys (Squish's DraftNumbers, which cost a week of silently lost saves):
 * `putFinite` leaves a NaN or infinite value *out* of an object, so it reads back as the
 * decoder's default; in an array a non-finite value is written as 0, because a dropped element
 * would shift every one after it. `JSONObject.put(double)` throws on NaN; this never throws.
 */
class JsonObject {
    private val map = LinkedHashMap<String, Any?>()

    val keys: Set<String> get() = map.keys
    fun has(name: String): Boolean = map.containsKey(name)
    /** The keys, in the order they were put (or read). */
    fun names(): List<String> = map.keys.toList()
    fun raw(name: String): Any? = map[name]

    fun put(name: String, value: String?): JsonObject { if (value == null) map.remove(name) else map[name] = value; return this }
    fun put(name: String, value: Int): JsonObject { map[name] = value.toLong(); return this }
    fun put(name: String, value: Long): JsonObject { map[name] = value; return this }
    fun put(name: String, value: Boolean): JsonObject { map[name] = value; return this }
    fun put(name: String, value: JsonObject?): JsonObject { if (value == null) map.remove(name) else map[name] = value; return this }
    fun put(name: String, value: JsonArray?): JsonObject { if (value == null) map.remove(name) else map[name] = value; return this }

    /** A float or double onto an object, or nothing at all when it is not finite. */
    fun putFinite(name: String, value: Double): JsonObject { if (value.isFinite()) map[name] = value else map.remove(name); return this }
    fun putFinite(name: String, value: Float): JsonObject = putFinite(name, value.toDouble())

    fun remove(name: String): JsonObject { map.remove(name); return this }

    fun string(name: String, default: String? = null): String? = (map[name] as? String) ?: default
    fun int(name: String, default: Int = 0): Int = (map[name] as? Number)?.toInt() ?: default
    fun long(name: String, default: Long = 0L): Long = (map[name] as? Number)?.toLong() ?: default
    /** The default, not NaN, when the field is missing (`optDouble` with no default answered NaN, and thirteen fields read it). */
    fun double(name: String, default: Double = 0.0): Double = (map[name] as? Number)?.toDouble() ?: default
    fun float(name: String, default: Float = 0f): Float = (map[name] as? Number)?.toFloat() ?: default
    fun bool(name: String, default: Boolean = false): Boolean = (map[name] as? Boolean) ?: default
    fun obj(name: String): JsonObject? = map[name] as? JsonObject
    fun arr(name: String): JsonArray? = map[name] as? JsonArray

    override fun equals(other: Any?): Boolean = other is JsonObject && other.map == map
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = Json.write(this)
}

class JsonArray(values: List<Any?> = emptyList()) {
    private val list = ArrayList<Any?>(values)
    val size: Int get() = list.size
    fun raw(i: Int): Any? = list[i]

    fun add(value: String?): JsonArray { list += value; return this }
    fun add(value: Long): JsonArray { list += value; return this }
    fun add(value: Int): JsonArray { list += value.toLong(); return this }
    fun add(value: Boolean): JsonArray { list += value; return this }
    fun add(value: JsonObject): JsonArray { list += value; return this }
    fun add(value: JsonArray): JsonArray { list += value; return this }
    /** A float into an array, or a zero in its place - see the file's note. */
    fun addFinite(value: Double): JsonArray { list += if (value.isFinite()) value else 0.0; return this }
    fun addFinite(value: Float): JsonArray = addFinite(value.toDouble())

    fun string(i: Int): String? = list[i] as? String
    fun long(i: Int, default: Long = 0L): Long = (list[i] as? Number)?.toLong() ?: default
    fun int(i: Int, default: Int = 0): Int = (list[i] as? Number)?.toInt() ?: default
    fun double(i: Int, default: Double = 0.0): Double = (list[i] as? Number)?.toDouble() ?: default
    fun float(i: Int, default: Float = 0f): Float = (list[i] as? Number)?.toFloat() ?: default
    fun bool(i: Int, default: Boolean = false): Boolean = (list[i] as? Boolean) ?: default
    fun obj(i: Int): JsonObject? = list[i] as? JsonObject
    fun arr(i: Int): JsonArray? = list[i] as? JsonArray

    fun objects(): List<JsonObject> = list.mapNotNull { it as? JsonObject }
    fun doubles(): DoubleArray = DoubleArray(size) { double(it) }
    fun floats(): FloatArray = FloatArray(size) { float(it) }
    fun longs(): LongArray = LongArray(size) { long(it) }
    fun strings(): List<String> = list.mapNotNull { it as? String }

    override fun equals(other: Any?): Boolean = other is JsonArray && other.list == list
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = Json.write(this)

    companion object {
        fun of(values: FloatArray): JsonArray = JsonArray().also { a -> values.forEach { a.addFinite(it) } }
        fun of(values: DoubleArray): JsonArray = JsonArray().also { a -> values.forEach { a.addFinite(it) } }
        fun of(values: LongArray): JsonArray = JsonArray().also { a -> values.forEach { a.add(it) } }
        fun ofStrings(values: List<String>): JsonArray = JsonArray().also { a -> values.forEach { a.add(it) } }
        fun ofObjects(values: List<JsonObject>): JsonArray = JsonArray().also { a -> values.forEach { a.add(it) } }
    }
}

class JsonException(message: String) : RuntimeException(message)

object Json {
    /** Parses [text] to a JsonObject, JsonArray, String, Long, Double, Boolean or null. */
    fun parse(text: String): Any? = Parser(text).document()

    fun parseObject(text: String): JsonObject = parse(text) as? JsonObject ?: throw JsonException("not an object")

    fun write(value: Any?, pretty: Boolean = false): String = StringBuilder().also { write(it, value, pretty, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Long, is Int -> sb.append(value)
            is Double -> sb.append(if (value.isFinite()) formatDouble(value) else "0")
            is Float -> sb.append(if (value.isFinite()) formatDouble(value.toDouble()) else "0")
            is Number -> sb.append(value.toString())
            is JsonObject -> {
                sb.append('{')
                var first = true
                for (k in value.keys) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    writeString(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value.raw(k), pretty, depth + 1)
                }
                if (pretty && !first) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                for (i in 0 until value.size) {
                    if (i > 0) sb.append(',')
                    write(sb, value.raw(i), pretty, depth + 1)
                }
                sb.append(']')
            }
            else -> throw JsonException("cannot write ${value::class.simpleName}")
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) { repeat(depth) { sb.append("  ") } }

    /** Whole numbers without a trailing ".0", everything else with the shortest round-tripping digits. */
    private fun formatDouble(d: Double): String {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val v = value()
            skipWs()
            if (i != s.length) throw JsonException("trailing characters at $i")
            return v
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(): Any? {
            skipWs()
            if (i >= s.length) throw JsonException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw JsonException("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal at $i")
            i += word.length
            return value
        }

        private fun obj(): JsonObject {
            val o = JsonObject()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return o }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw JsonException("expected key at $i")
                val k = string()
                skipWs()
                if (i >= s.length || s[i] != ':') throw JsonException("expected ':' at $i")
                i++
                when (val v = value()) {
                    null -> o.put(k, null as String?)
                    is String -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    is Long -> o.put(k, v)
                    is Double -> o.putFinite(k, v)
                    is JsonObject -> o.put(k, v)
                    is JsonArray -> o.put(k, v)
                }
                skipWs()
                if (i >= s.length) throw JsonException("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return o }
                throw JsonException("expected ',' or '}' at $i")
            }
        }

        private fun arr(): JsonArray {
            val a = JsonArray()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                when (val v = value()) {
                    null -> a.add(null as String?)
                    is String -> a.add(v)
                    is Boolean -> a.add(v)
                    is Long -> a.add(v)
                    is Double -> a.addFinite(v)
                    is JsonObject -> a.add(v)
                    is JsonArray -> a.add(v)
                }
                skipWs()
                if (i >= s.length) throw JsonException("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return a }
                throw JsonException("expected ',' or ']' at $i")
            }
        }

        private fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { if (i + 4 > s.length) throw JsonException("bad escape"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonException("unterminated string")
        }

        private fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') { isDouble = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { isDouble = true; i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++; while (i < s.length && s[i].isDigit()) i++ }
            val text = s.substring(start, i)
            return if (isDouble) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        }
    }
}
