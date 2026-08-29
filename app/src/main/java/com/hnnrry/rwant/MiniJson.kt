package com.hnnrry.rwant

/**
 * 极简 JSON 解析 / 序列化（MCP 通道的地基，沿用 Ridea 已验证实现）。
 *
 * 自己写的原因：铁律「不引入重型依赖」—— 每多一个三方库就多一分 Gradle 依赖风险；
 * MCP（JSON-RPC 2.0）需要的只是标准 JSON 的双向转换，几百行纯 Kotlin 足够。
 *
 * 解析产物（任意 JSON 树）：
 *   object  -> LinkedHashMap<String, Any?>
 *   array   -> ArrayList<Any?>
 *   string  -> String
 *   number  -> Long（整数）/ Double（带小数或指数）
 *   boolean -> Boolean
 *   null    -> null
 */
object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("JSON 末尾有多余内容：${text.takeLast(20)}")
        return value
    }

    fun parseOrNull(text: String): Any? = runCatching { parse(text) }.getOrNull()

    private class Parser(private val src: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("JSON 意外结束")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): LinkedHashMap<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && src[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                if (atEnd() || src[pos] != '"') throw IllegalArgumentException("JSON 对象的键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || src[pos] != ':') throw IllegalArgumentException("JSON 对象缺少冒号")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON 对象未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("JSON 对象缺少逗号或结束花括号")
                }
            }
        }

        private fun parseArray(): ArrayList<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && src[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON 数组未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("JSON 数组缺少逗号或结束方括号")
                }
            }
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("JSON 字符串未闭合")
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (atEnd()) throw IllegalArgumentException("JSON 转义未闭合")
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
                        if (pos + 4 > src.length) throw IllegalArgumentException("非法 \\u 转义：${src.substring(pos)}")
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        val code = hex.toIntOrNull(16) ?: throw IllegalArgumentException("非法 \\u 转义：$hex")
                        sb.append(code.toChar())
                    }
                    else -> throw IllegalArgumentException("非法转义字符：\\$esc")
                }
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (src.regionMatches(pos, word, 0, word.length)) { pos += word.length; return value }
            throw IllegalArgumentException("非法 JSON 字面量（位置 $pos）")
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && (src[pos] == '-' || src[pos] == '+')) pos++
            var hasFractionOrExponent = false
            while (!atEnd()) {
                val c = src[pos]
                when {
                    c.isDigit() -> pos++
                    c == '.' || c == 'e' || c == 'E' -> { hasFractionOrExponent = true; pos++ }
                    c == '-' || c == '+' -> pos++
                    else -> break
                }
            }
            val text = src.substring(start, pos)
            if (text.isEmpty() || text == "-" || text == "+") throw IllegalArgumentException("非法 JSON 数字（位置 $start）")
            return if (hasFractionOrExponent) {
                text.toDoubleOrNull() ?: throw IllegalArgumentException("非法 JSON 数字：$text")
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull()
                ?: throw IllegalArgumentException("非法 JSON 数字：$text")
            }
        }
    }

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, value)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                when {
                    d.isNaN() || d.isInfinite() -> sb.append("null")
                    d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.007199254740992E15 -> sb.append(d.toLong().toString())
                    else -> sb.append(d.toString())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString()); sb.append(':'); writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('['); var first = true
                for (v in value) { if (!first) sb.append(','); first = false; writeTo(sb, v) }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format(java.util.Locale.US, "%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
