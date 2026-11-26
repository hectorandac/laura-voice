package dev.hect.lauravoice.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class AvatarModelTest {
    private fun goldenDir(): File {
        val u = javaClass.getResource("/golden/idle.json") ?: error("golden resources missing: run tools/avatar-export/export.sh golden")
        return File(u.toURI()).parentFile
    }

    /** Steps the native model through a golden case exactly as the WebView export did. */
    private fun run(case: Map<String, Any?>): AvatarModel {
        val m = AvatarModel(watch = (case["watch"] as? Number)?.toInt() == 1)
        m.theme = case["theme"] as String
        m.small = (case["small"] as? Number)?.toInt() == 1
        m.startCase(case["state"] as String, (case["emotion"] as? String) ?: "none")
        m.micInput = ((case["mic"] as? Number) ?: 0).toFloat()
        m.ttsInput = ((case["tts"] as? Number) ?: 0).toFloat()
        val frames = case["frames"].i(); val reactAt = (case["reactAt"] as? Number)?.toInt() ?: -1
        for (f in 1..frames) m.stepCase(if (f == reactAt) case["reaction"] as String else null)
        return m
    }

    /** The native port draws the same instances, with the same matrices and attributes, as the WebView for every golden case. */
    @Test fun matchesWebViewGoldens() {
        val names = listOf("matrix" to 0..15, "lit" to 16..18, "shd" to 19..21, "fx" to 22..25, "x" to 26..29, "y" to 30..33, "n" to 34..36)
        val fails = ArrayList<String>()
        val files = goldenDir().listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 10)
        for (f in files) {
            val g = Json.parse(f.readText()).obj()
            val case = g["case"].obj()
            val m = run(case)
            val web = g["inst"].fv(); val n = g["n"].i(); val S = AvatarModel.STRIDE
            val webBy = (0 until n).associateBy { web[it * S + 27].toInt() }
            val natBy = (0 until m.count).associateBy { m.instances[it * S + 27].toInt() }
            val missing = webBy.keys - natBy.keys; val extra = natBy.keys - webBy.keys
            val worst = DoubleArray(names.size); val worstAt = IntArray(names.size)
            for ((id, wi) in webBy) {
                val ni = natBy[id] ?: continue
                for ((k, pr) in names.withIndex()) for (j in pr.second) {
                    val dlt = abs(web[wi * S + j] - m.instances[ni * S + j]).toDouble()
                    if (dlt > worst[k]) { worst[k] = dlt; worstAt[k] = id }
                }
            }
            val bad = names.indices.filter { worst[it] > 2e-3 }
            val line = "${f.name}: web ${n} native ${m.count} missing ${missing.sorted().take(8)} extra ${extra.sorted().take(8)} " +
                names.indices.joinToString(" ") { "${names[it].first}=%.4f@%d".format(worst[it], worstAt[it]) }
            println(line)
            if (missing.isNotEmpty() || extra.isNotEmpty() || bad.isNotEmpty()) fails += line
            // per-frame uniforms
            val u = g["u"].obj()
            fun near(name: String, a: Float, b: Float) { if (abs(a - b) > 2e-3) fails += "${f.name}: $name web $b native $a" }
            near("uLightI", m.uLightI, u["uLightI"].f()); near("uHairK", m.uHairK, u["uHairK"].f()); near("uPartK", m.uPartK, u["uPartK"].f())
            near("uWake", m.uWake, u["uWake"].f()); near("uDesat", m.uDesat, u["uDesat"].f()); near("uSkinK", m.uSkinK, u["uSkinK"].f())
            near("uScan", m.uScan, u["uScan"].f())
            val ul = u["uLight"].fv(); for (j in 0..2) near("uLight$j", m.uLight[j], ul[j])
            val rc = u["uRimCol"].fv(); for (j in 0..2) near("uRimCol$j", m.uRimCol[j], rc[j])
            val hu = u["uHairUp"].fv(); for (j in 0..3) near("uHairUp$j", m.uHairUp[j], hu[j])
            val bi = u["uBodyInv"].fv(); for (j in 0..8) near("uBodyInv$j", m.uBodyInv[j], bi[j])
            val hd = u["uHead"].fv(); for (j in 0..15) near("uHead$j", m.uHead[j], hd[j])
            val ex = g["extra"].obj(); val inr = ex["inner"].fv(); for (j in 0..9) near("inner$j", m.inner[j], inr[j])
            val halo = ex["halo"].fv(); near("haloAlpha", m.haloAlpha, halo[0]); near("haloY", m.haloY, halo[1])
            val fl = ex["floor"].fv(); near("floorAlpha", m.floorAlpha, fl[0]); near("floorScale", m.floorScaleX, fl[1])
            assertEquals(f.name, ex["fps"].i(), m.autoFps(true))
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun allStatesAndReactionsStayFinite() {
        val m = AvatarModel()
        for (s in listOf("idle", "listening", "thinking", "speaking", "sleeping", "offline", "error", "notowner", "interrupted", "ignored")) {
            m.setBase(s)
            m.micInput = 0.6f
            m.ttsInput = 0.7f
            for (r in AvatarModel.REACTIONS) {
                m.triggerReaction(r)
                repeat(10) { m.step(1 / 30f) }
            }
            m.car = !m.car
            repeat(60) { m.step(1 / 30f) }
            assertTrue(s, (0 until m.count * AvatarModel.STRIDE).all { m.instances[it].isFinite() })
            assertTrue(s, m.haloAlpha.isFinite() && m.uLight.all { it.isFinite() })
        }
    }

    @Test fun watchModelIsReduced() {
        val full = AvatarModel(); val w = AvatarModel(watch = true)
        repeat(60) { full.step(1 / 30f); w.step(1 / 30f) }
        println("instances drawn: phone ${full.count} watch ${w.count} (pool ${full.poolSize} / ${w.poolSize})")
        assertTrue(w.count < full.count)
    }
}
