package dev.hect.lauravoice.core

/** Which model is answering: the gateway's fast local model or Laura's main model through Hermes. */
data class Source(val engine: String, val model: String = "", val lane: String? = null) {
    val local: Boolean get() = engine == "local"

    val label: String
        get() = when {
            local -> "Local · " + pretty(model).ifEmpty { "fast model" }
            model.isEmpty() -> "Laura's main model · via Hermes"
            else -> pretty(model) + " · via Hermes"
        }

    companion object {
        /** "bonsai-9b" -> "Bonsai 9B", "claude-sonnet-5-5" -> "Claude Sonnet 5.5". */
        fun pretty(model: String): String {
            val out = mutableListOf<String>()
            for (t in model.split('-', '_', ' ').filter { it.isNotEmpty() }) {
                val last = out.lastOrNull()
                when {
                    t.all { it.isDigit() } && last != null && last.last().isDigit() -> out[out.size - 1] = "$last.$t"
                    t.first().isDigit() -> out += t.uppercase()
                    else -> out += t.replaceFirstChar { it.uppercase() }
                }
            }
            return out.joinToString(" ")
        }
    }
}
