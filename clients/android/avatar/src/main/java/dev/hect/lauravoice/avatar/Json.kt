package dev.hect.lauravoice.avatar

/** Minimal JSON reader (objects -> Map, arrays -> List, numbers -> Double), so the model loads the same on the JVM and Android. */
internal class Json private constructor(private val s: String) {
    private var i = 0

    private fun ws() { while (i < s.length && s[i] <= ' ') i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> list()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> if (c == '-' || c in '0'..'9') num() else error("json: unexpected '$c' at $i")
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); check(s[i++] == ':')
            m[k] = value(); ws()
            if (s[i++] == '}') return m
        }
    }

    private fun list(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l += value(); ws()
            if (s[i++] == ']') return l
        }
    }

    private fun str(): String {
        check(s[i++] == '"')
        val b = StringBuilder()
        while (true) {
            val c = s[i++]
            if (c == '"') return b.toString()
            if (c != '\\') { b.append(c); continue }
            when (val e = s[i++]) {
                'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> b.append(e)
            }
        }
    }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i] in "+-.eE" || s[i] in '0'..'9')) i++
        return s.substring(st, i).toDouble()
    }

    companion object {
        fun parse(text: String): Any? = Json(text).value()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj() = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.arr() = this as List<Any?>
internal fun Any?.d() = (this as Number).toDouble()
internal fun Any?.f() = (this as Number).toFloat()
internal fun Any?.i() = (this as Number).toInt()
internal fun Any?.dv() = arr().let { l -> DoubleArray(l.size) { l[it].d() } }
internal fun Any?.fv() = arr().let { l -> FloatArray(l.size) { l[it].f() } }
