package dev.hect.lauravoice.widget

import android.content.Context

/** Per-widget choices from [WidgetConfigActivity]. */
data class WidgetConfig(
    /** "auto" (follows the system), "light" or "dark". */
    val theme: String = "auto",
    /** "live" (her state as a 3D still), "tile" (the stills on her dark render background), "icon" (the launcher face). */
    val face: String = "live",
    /** Just her face at any size. */
    val faceOnly: Boolean = false,
    /** Her latest reply and who gave it, on 4x3 and larger. */
    val showReply: Boolean = true,
    /** Keep who answered, hide what she said. Lock screen widgets always hide it. */
    val hideText: Boolean = false,
) {
    companion object {
        val THEMES = listOf("auto", "light", "dark")
        val FACES = listOf("live", "tile", "icon")
    }
}

/** Widget settings (keyed by appWidgetId) and the last reply, which outlives the process for the widget's sake. */
object WidgetPrefs {
    private const val FILE = "laura_widget"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context, id: Int): WidgetConfig {
        val p = p(ctx)
        val d = WidgetConfig()
        return WidgetConfig(
            theme = p.getString("w$id.theme", d.theme)!!.takeIf { it in WidgetConfig.THEMES } ?: d.theme,
            face = p.getString("w$id.face", d.face)!!.takeIf { it in WidgetConfig.FACES } ?: d.face,
            faceOnly = p.getBoolean("w$id.faceOnly", d.faceOnly),
            showReply = p.getBoolean("w$id.showReply", d.showReply),
            hideText = p.getBoolean("w$id.hideText", d.hideText),
        )
    }

    fun save(ctx: Context, id: Int, c: WidgetConfig) = p(ctx).edit()
        .putString("w$id.theme", c.theme).putString("w$id.face", c.face).putBoolean("w$id.faceOnly", c.faceOnly)
        .putBoolean("w$id.showReply", c.showReply).putBoolean("w$id.hideText", c.hideText).apply()

    fun delete(ctx: Context, ids: IntArray) {
        val e = p(ctx).edit()
        for (id in ids) for (k in listOf("theme", "face", "faceOnly", "showReply", "hideText")) e.remove("w$id.$k")
        e.apply()
    }

    /** Some widget would show reply text: only then is the text kept on disk. */
    fun anyShowsText(ctx: Context, ids: IntArray): Boolean = ids.any { load(ctx, it).let { c -> c.showReply && !c.hideText && !c.faceOnly } }

    fun saveReply(ctx: Context, r: Reply?, keepText: Boolean) {
        val e = p(ctx).edit()
        if (r == null) e.remove("reply.text").remove("reply.source").remove("reply.local").remove("reply.time")
        else e.putString("reply.text", if (keepText) r.text else "").putString("reply.source", r.source)
            .putBoolean("reply.local", r.local).putLong("reply.time", r.time)
        e.apply()
    }

    fun loadReply(ctx: Context): Reply? {
        val p = p(ctx)
        val src = p.getString("reply.source", null) ?: return null
        return Reply(p.getString("reply.text", "")!!, src, p.getBoolean("reply.local", false), p.getLong("reply.time", 0L))
    }
}
