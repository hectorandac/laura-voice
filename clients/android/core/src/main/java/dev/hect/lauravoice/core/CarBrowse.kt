package dev.hect.lauravoice.core

/**
 * The media browse tree Laura shows in Android Auto, and who may browse it. Android Auto only shows browsable items
 * at the root (as tabs), so "Talk to Laura" sits one level down under a "Laura" tab. Nothing here depends on car
 * mode: Android Auto has to get a root before it connects to the car, or Laura isn't listed.
 */
object CarBrowse {
    const val ROOT = "laura_root"
    const val HOME = "laura"
    const val TALK = "talk"

    data class Node(val id: String, val browsable: Boolean)

    fun children(parentId: String): List<Node> = when (parentId) {
        ROOT -> listOf(Node(HOME, browsable = true))
        HOME -> listOf(Node(TALK, browsable = false))
        else -> emptyList()
    }

    /** Media browsers that drive cars and assistants (Android Auto, Assistant/Gemini, Play services' car module). */
    val TRUSTED = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.carassistant",
        "com.google.android.googlequicksearchbox",
        "com.google.android.autosimulator",
        "com.google.android.gms",
        "com.android.systemui",
        "com.android.bluetooth",
    )

    /**
     * Whether a browser gets the root. The docs require a root for system apps (Android Auto, AAOS media centers)
     * and Assistant; other third-party apps are refused, since Play turns the microphone on.
     */
    fun allowed(pkg: String, uid: Int, myUid: Int, systemUid: Int, systemApp: Boolean, mediaControl: Boolean): Boolean =
        uid == myUid || uid == systemUid || pkg in TRUSTED || systemApp || mediaControl
}
