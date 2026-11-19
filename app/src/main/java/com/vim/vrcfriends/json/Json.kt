package com.vim.vrcfriends.json

/**
 * 极简 JSON（零依赖，纯 Kotlin）。
 *
 * 只做本 App 需要的部分：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 不支持注释、NaN、BigInt —— VRChat REST 返回里都没有。
 * 解析失败抛 [JsonException]，不做静默容错（错的数据宁可报错，也不假装成空）。
 */
sealed class Json {
    data class Obj(val fields: Map<String, Json>) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    object Null : Json()

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)

    fun string(key: String): String? = when (val v = get(key)) {
        is Str -> v.value
        is Num -> formatNumber(v.value)
        is Bool -> v.value.toString()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = get(key)) {
        is Bool -> v.value
        is Str -> when (v.value) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    fun int(key: String): Int? = when (val v = get(key)) {
        is Num -> v.value.toInt()
        is Str -> v.value.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun array(key: String): List<Json>? = (get(key) as? Arr)?.items

    fun asArray(): List<Json>? = (this as? Arr)?.items

    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> formatNumber(value)
        else -> null
    }

    fun obj(): Map<String, Json>? = (this as? Obj)?.fields

    companion object {
        fun formatNumber(d: Double): String =
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
}

class JsonException(message: String) : Exception(message)

object JsonParser {
    fun parse(text: String): Json {
        val p = Cursor(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.eof()) throw JsonException("trailing garbage at ${p.pos}")
        return v
    }

    private class Cursor(val s: String) {
        var pos = 0
        fun eof() = pos >= s.length
        fun peek(): Char = if (eof()) throw JsonException("unexpected end") else s[pos]

        fun skipWs() {
            while (!eof() && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun readValue(depth: Int): Json {
            if (depth > 64) throw JsonException("nesting too deep")
            skipWs()
            return when (peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> Json.Str(readString())
                't' -> { expectWord("true"); Json.Bool(true) }
                'f' -> { expectWord("false"); Json.Bool(false) }
                'n' -> { expectWord("null"); Json.Null }
                else -> readNumber()
            }
        }

        private fun expectWord(w: String) {
            if (!s.startsWith(w, pos)) throw JsonException("expected $w at $pos")
            pos += w.length
        }

        private fun readObject(depth: Int): Json {
            pos++ // {
            val map = LinkedHashMap<String, Json>()
            skipWs()
            if (peek() == '}') { pos++; return Json.Obj(map) }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("expected key at $pos")
                val k = readString()
                skipWs()
                if (peek() != ':') throw JsonException("expected ':' at $pos")
                pos++
                map[k] = readValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return Json.Obj(map) }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): Json {
            pos++ // [
            val list = ArrayList<Json>()
            skipWs()
            if (peek() == ']') { pos++; return Json.Arr(list) }
            while (true) {
                list.add(readValue(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return Json.Arr(list) }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonException("unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw JsonException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Json {
            val start = pos
            if (!eof() && (s[pos] == '-' || s[pos] == '+')) pos++
            while (!eof() && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' ||
                        s[pos] == '-' || s[pos] == '+')
            ) pos++
            val raw = s.substring(start, pos)
            val d = raw.toDoubleOrNull() ?: throw JsonException("bad number '$raw' at $start")
            return Json.Num(d)
        }
    }
}
