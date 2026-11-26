package dev.hect.lauravoice.avatar

/**
 * The avatar as data: laura-model.json, exported from the WebView renderer (index.html) by
 * clients/android/tools/avatar-export/export.sh. Geometry, palettes, face bitmaps, state tables and reaction
 * timelines all come from there; only the per-frame logic is ported code.
 */
internal class LauraData(root: Map<String, Any?>) {
    class Item(val i: Int, val k: Int, m: Map<String, Any?>) {
        // body (k = SHELL): rest transform in body space
        val p = (m["p"] as? List<*>)?.dv()
        val q = (m["q"] as? List<*>)?.dv()
        val s = (m["s"] as? List<*>)?.dv()
        val fringe = (m["fringe"] as? Number)?.toInt() == 1
        val drop = (m["drop"] as? Number)?.toInt() ?: 0
        val gone = (m["gone"] as? Number)?.toInt() == 1
        val wpx = (m["wpx"] as? Number)?.toInt() ?: 0
        // face, tail, charm, motes
        val side = (m["side"] as? Number)?.toInt() ?: 0
        val r = (m["r"] as? Number)?.toInt() ?: 0
        val c = (m["c"] as? Number)?.toInt() ?: 0
        val lash = (m["lash"] as? Number)?.toInt() == 1
        val size = (m["size"] as? Number)?.toDouble() ?: 0.0
        val flat = (m["flat"] as? Number)?.toDouble() ?: 0.0
        val qi = (m["q"] as? Number)?.toInt() ?: 0
        val j = (m["j"] as? Number)?.toInt() ?: 0
        val spark = (m["spark"] as? Number)?.toInt() == 1
        val x = (m["x"] as? Number)?.toDouble() ?: 0.0
        val y = (m["y"] as? Number)?.toDouble() ?: 0.0
        val sx = (m["sx"] as? Number)?.toDouble() ?: 1.0
        val sy = (m["sy"] as? Number)?.toDouble() ?: 1.0
        val bk = (m["bk"] as? Number)?.toDouble() ?: 0.0
        val fz = (m["fz"] as? Number)?.toDouble() ?: 0.0
        val idx = (m["idx"] as? Number)?.toInt() ?: 0
        val cn = (m["cn"] as? Number)?.toInt() ?: 0
        val o = (m["o"] as? List<*>)?.dv()
        val core = (m["core"] as? Number)?.toInt() == 1
        val tassel = (m["tassel"] as? Number)?.toInt() ?: -1
    }

    class Theme(m: Map<String, Any?>) {
        val name = m["name"] as String
        val col: Map<String, DoubleArray> = m["col"].obj().mapValues { it.value.dv() }
        val num: Map<String, Double> = m["num"].obj().mapValues { it.value.d() }
        val mouthFlat = m["mouthFlat"].dv(); val catchLit = m["catchLit"].dv()
        val uSkinFD = m["uSkinFD"].fv(); val uCheek = m["uCheek"].fv(); val uSkinRim = m["uSkinRim"].fv(); val uSkinFill = m["uSkinFill"].fv()
        val uRampZ = m["uRampZ"].f()
        val lit = m["lit"].fv(); val shd = m["shd"].fv(); val fx = m["fx"].fv(); val x = m["x"].fv(); val y = m["y"].fv()
        fun c(k: String) = col.getValue(k)
        fun n(k: String) = num.getValue(k)
    }

    /** One eye or mouth spec: a name, or a pair (viewer-left eye, viewer-right eye). */
    data class Eyes(val l: String, val r: String = l) { fun of(side: Int) = if (side < 0) l else r }

    class Reaction(val name: String, m: Map<String, Any?>) {
        val d = m["d"].d()
        val rate = m["rate"].d()
        val keys: Map<String, DoubleArray> = m["keys"].obj().mapValues { it.value.dv() }
        val eyes: List<Eyes?> = m["eyes"].arr().map { e -> when (e) { null -> null; is String -> Eyes(e); else -> e.arr().let { Eyes(it[0] as String, it[1] as String) } } }
        val mouth: List<String?> = m["mouth"].arr().map { it as String? }
        val tint: List<String?> = m["tint"].arr().map { it as String? }
        val tintAmt = m["tintAmt"].dv()
        val n = mouth.size
    }

    val vs = root["VS"].d(); val piv = root["PIV"].d()
    val eyeCx = root["EYE_CX"].d(); val eyeCy = root["EYE_CY"].d(); val mouthY = root["MOUTH_Y"].d()
    val fs = root["FS"].d(); val ms = root["MS"].d(); val loopL = root["L"].d(); val glow = root["GLOW"].d()
    val fb = root["FB"].obj().mapValues { it.value.d() }
    val fov = root["fov"].d()
    private val tail = root["TAIL"].obj()
    val tailRoot = tail["root"].dv(); val nr = tail["n"].i(); val tailSeg = tail["seg"].d(); val tailAlt = tail["alt"].d(); val tailOv = tail["ov"].d()
    val tailKeep = tail["keep"].d(); val tailRate = tail["rate"].d(); val chainSeg = tail["chainSeg"].d()
    val tailW = root["TAIL_W"].dv()
    val restS = root["REST_S"].arr().map { it.dv() }
    val ribPoses: Map<String, List<DoubleArray>> = root["RIB_POSES"].obj().mapValues { e -> e.value.arr().map { it.dv() } }
    val ribKeys: List<Pair<String, String>> = root["RIB_KEYS"].arr().map { it.arr().let { p -> p[0] as String to p[1] as String } }
    private val tsw = root["TAIL_SW"].obj()
    val swW = tsw["w"].d(); val swZ = tsw["z"].d(); val swMax = tsw["max"].d()
    val charmAt = root["CHARM_AT"].dv(); val crownSz = root["CROWN_SZ"].dv(); val sleepSz = root["SLEEP_SZ"].dv()
    val gemBase = root["gemBase"].dv(); val rot45 = root["ROT45"].dv(); val spark = root["spark"].dv(); val mouthZ0 = root["mouthZ0"].d()
    val innerR = root["INNER_R"].dv(); val innerY = root["INNER_Y"].d()
    val hbandFlat = root["HBAND"].obj()["flat"].d(); val hbandFlatZ = root["HBAND"].obj()["flatZ"].d()
    val uni: Map<String, FloatArray> = root["uni"].obj().mapValues { if (it.value is Number) floatArrayOf(it.value.f()) else it.value.fv() }
    val themes: Map<String, Theme> = root["themes"].obj().mapValues { Theme(it.value.obj()) }
    val lights: Map<String, DoubleArray> = root["lights"].obj().mapValues { it.value.dv() }
    val tints: Map<String, DoubleArray> = root["tints"].obj().mapValues { it.value.dv() }
    val eyes: Map<String, List<String>> = root["EYES"].obj().mapValues { e -> e.value.arr().map { it as String } }
    val eyeDy: Map<String, Double> = root["EYE_DY"].obj().mapValues { it.value.d() }
    val iris: Map<String, IntArray> = root["IRIS"].obj().mapValues { e -> e.value.arr().map { it.i() }.toIntArray() }
    val catches: Map<String, List<DoubleArray>> = root["CATCHES"].obj().mapValues { e -> e.value.arr().map { it.dv() } }
    val lashes: Map<String, DoubleArray> = root["LASH"].obj().mapValues { it.value.dv() }
    val mouths: Map<String, List<String>> = root["MOUTHS"].obj().mapValues { e -> e.value.arr().map { it as String } }
    val speak: List<List<String>> = root["SPEAK"].arr().map { p -> p.arr().map { it as String } }
    val litMouth: Set<String> = root["LIT_MOUTH"].obj().keys
    val openLike: Set<String> = root["OPEN_LIKE"].obj().keys
    val baseKeys: List<String> = root["BASE"].obj().keys.toList()
    val base: DoubleArray = root["BASE"].obj().values.map { it.d() }.toDoubleArray()
    val fast: Map<String, Double> = root["FAST"].obj().mapValues { it.value.d() }
    val hairK: Map<String, Double> = root["HAIRK"].obj().mapValues { it.value.d() }
    val numKeys: List<String> = root["NUM_KEYS"].arr().map { it as String }
    val rmDrop: Set<String> = root["RM_DROP"].obj().keys
    val reactions: Map<String, Reaction> = root["reactions"].obj().mapValues { Reaction(it.key, it.value.obj()) }
    val faceZ: Map<Long, Double> = root["faceZ"].obj().entries.associate { (k, v) -> k.split(',').let { key(it[0].toInt(), it[1].toInt()) } to v.d() }
    val items: List<Item> = root["items"].arr().mapIndexed { i, m -> Item(i, m.obj()["k"].i(), m.obj()) }
    val n = root["n"].fv()

    companion object {
        const val SHELL = 0; const val EYE = 2; const val MOUTH = 3; const val BLUSH = 4; const val HAND = 5; const val SAT = 6
        const val CATCH = 7; const val RIB = 8; const val CHARM = 9; const val TIE = 10; const val WRAP = 11
        fun key(x: Int, j: Int) = (x.toLong() shl 32) or (j.toLong() and 0xffffffffL)

        val shared: LauraData by lazy {
            val text = LauraData::class.java.getResourceAsStream("/dev/hect/lauravoice/avatar/laura-model.json")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            LauraData(Json.parse(text).obj())
        }
    }
}
