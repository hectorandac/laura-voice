package dev.hect.lauravoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CarBrowseTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun rootHasOnlyBrowsableTabsAndTalkIsBelow() {
        val root = CarBrowse.children(CarBrowse.ROOT)
        assertTrue(root.isNotEmpty() && root.size <= 4 && root.all { it.browsable })
        val talk = root.flatMap { CarBrowse.children(it.id) }.single()
        assertEquals(CarBrowse.TALK, talk.id)
        assertFalse(talk.browsable)
    }

    @Test fun androidAutoGetsARootWithoutCarMode() {
        fun ok(pkg: String, uid: Int = 10200, system: Boolean = false, mcc: Boolean = false) =
            CarBrowse.allowed(pkg, uid, myUid = 10100, systemUid = 1000, systemApp = system, mediaControl = mcc)
        assertTrue(ok("com.google.android.projection.gearhead"))
        assertTrue(ok("com.google.android.googlequicksearchbox"))
        assertTrue(ok("com.example.carmedia", system = true))
        assertTrue(ok("com.example.controller", mcc = true))
        assertTrue(ok("dev.hect.lauravoice", uid = 10100))
        assertTrue(ok("android", uid = 1000))
        assertFalse(ok("com.example.random"))
    }

    /** What Android Auto needs to list a media app (developer.android.com/training/cars/media/configure-manifest, /auto). */
    @Test fun manifestDeclaresAndroidAutoMedia() {
        val res = File("../app/src/main")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val app = doc.parse(File(res, "AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        fun Element.kids(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == tag }

        val service = app.kids("service").single { it.getAttributeNS(android, "name") == ".car.LauraMediaService" }
        assertEquals("true", service.getAttributeNS(android, "exported"))
        assertTrue(service.getAttributeNS(android, "enabled") != "false")
        assertTrue(service.getAttributeNS(android, "permission").isEmpty())
        val actions = service.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(android, "name") } }
        assertTrue("android.media.browse.MediaBrowserService" in actions)

        val meta = app.kids("meta-data").associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "resource") }
        assertEquals("@xml/automotive_app_desc", meta["com.google.android.gms.car.application"])
        assertTrue(meta["androidx.car.app.TintableAttributionIcon"].orEmpty().startsWith("@drawable/"))
        assertTrue(app.getAttributeNS(android, "icon").isNotEmpty())

        val uses = doc.parse(File(res, "res/xml/automotive_app_desc.xml")).getElementsByTagName("uses")
        assertTrue((0 until uses.length).any { (uses.item(it) as Element).getAttribute("name") == "media" })
    }
}
