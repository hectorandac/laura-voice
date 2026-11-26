package dev.hect.lauravoice.core

/**
 * Things said to Laura that the app does itself instead of answering: for now only "open settings" /
 * "설정 열어줘". Matched on the final transcript of an addressed turn, as the whole utterance (with her name and
 * "please" around it), so a sentence that only mentions settings is answered as usual.
 */
object VoiceCommands {
    const val OPEN_SETTINGS = "open_settings"

    private const val NAME = "(?:(?:hey|hi|ok|okay)\\s+)?(?:laura|lora|lorra|laurah)"
    private val EN = Regex(
        "^(?:$NAME\\s*,?\\s*)?(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:open|show(?: me)?|go to|take me to|bring up|pull up)\\s+(?:the\\s+|your\\s+|my\\s+|app\\s+)?" +
            "(?:settings|preferences|options|setting)(?:\\s+(?:page|screen|menu))?" +
            "(?:\\s*,?\\s*please)?(?:\\s*,?\\s*$NAME)?$"
    )
    private val EN_BARE = Regex("^(?:$NAME\\s*,?\\s*)?settings(?:\\s*,?\\s*please)?$")
    private val KO = Regex(
        "^(?:(?:로라|라우라|로라야)\\s*,?\\s*)?(?:앱\\s*)?(?:환경\\s*)?설정\\s*(?:창|화면|메뉴)?\\s*(?:을|를|으로|로)?\\s*(?:좀\\s*)?" +
            "(?:열어|열어줘|열어 줘|열어주세요|열어 주세요|보여줘|보여 줘|보여주세요|켜줘|켜 줘|가자|가줘|들어가줘)$"
    )

    /** The command in [text], or null when it should be answered normally. */
    fun match(text: String): String? {
        val t = text.lowercase().trim().replace(Regex("[.!?。~]+$"), "").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.split(' ').size > 9) return null
        return if (EN.matches(t) || EN_BARE.matches(t) || KO.matches(t)) OPEN_SETTINGS else null
    }
}
