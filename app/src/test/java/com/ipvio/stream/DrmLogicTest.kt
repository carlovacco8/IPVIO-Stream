package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmLogicTest {
    private val kid = "000102030405060708090a0b0c0d0e0f"
    private val key = "101112131415161718191a1b1c1d1e1f"

    @Test fun clearKey_hexPair_becomesJwk() {
        val i = DrmLogic.fromProps(listOf(
            "#KODIPROP:inputstream.adaptive.license_type=clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=$kid:$key"))!!
        assertEquals(DrmLogic.Type.CLEARKEY, i.type)
        assertEquals("{\"keys\":[{\"kty\":\"oct\",\"k\":\"EBESExQVFhcYGRobHB0eHw\",\"kid\":\"AAECAwQFBgcICQoLDA0ODw\"}],\"type\":\"temporary\"}", i.clearKeys)
        assertNull(i.licenseUrl)
    }

    @Test fun clearKey_jsonMap_andGuessedType() {
        val i = DrmLogic.fromProps(listOf("#KODIPROP:inputstream.adaptive.license_key={\"$kid\":\"$key\"}"))!!
        assertEquals(DrmLogic.Type.CLEARKEY, i.type)
        assertTrue(i.clearKeys!!.contains("AAECAwQFBgcICQoLDA0ODw"))
    }

    @Test fun widevine_kodiStyleHeaders() {
        val i = DrmLogic.fromProps(listOf(
            "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha",
            "#KODIPROP:inputstream.adaptive.license_key=https://lic.example.com/wv?x=1|User-Agent=Foo%20Bar&Referer=https://a.b/|R{SSM}|",
            "#KODIPROP:inputstream.adaptive.manifest_type=mpd"))!!
        assertEquals(DrmLogic.Type.WIDEVINE, i.type)
        assertEquals("https://lic.example.com/wv?x=1", i.licenseUrl)
        assertEquals(mapOf("User-Agent" to "Foo Bar", "Referer" to "https://a.b/"), i.headers)
        assertEquals("mpd", i.mime)
    }

    @Test fun drmLegacy() {
        val i = DrmLogic.fromProps(listOf("#KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|$kid:$key"))!!
        assertEquals(DrmLogic.Type.CLEARKEY, i.type)
        assertNotNull(i.clearKeys)
    }

    @Test fun attachAndRead_roundTrip() {
        val i = DrmLogic.Info(DrmLogic.Type.WIDEVINE, "https://l.example/x?a=1&b=2", mapOf("X-Tok" to "a&b=c"), null, "mpd")
        val u = DrmLogic.attach("https://cdn.example/live/ch.mpd?t=9", i)
        assertTrue(DrmLogic.has(u))
        assertEquals("https://cdn.example/live/ch.mpd?t=9", DrmLogic.base(u))
        assertEquals(i, DrmLogic.of(u))
        assertFalse(u.substring(u.indexOf('#')).contains('/'))
    }

    @Test fun noProps_noChange() {
        assertEquals("http://a/b.ts", DrmLogic.withProps("http://a/b.ts", emptyList()))
        assertEquals("http://a/b.ts", DrmLogic.withProps("http://a/b.ts", listOf("#KODIPROP:inputstream.adaptive.foo=bar")))
        assertNull(DrmLogic.of("http://a/b.ts"))
    }

    @Test fun parser_attachesPropsToEntry_andClassifiesByBase() {
        val m3u = """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=$kid:$key
            #EXTINF:-1 tvg-id="a" group-title="News",Channel A
            https://cdn.example/a/manifest.mpd
            #EXTINF:-1 group-title="News",Channel B
            http://srv/live/u/p/2.ts
            #EXTINF:-1 group-title="Films",Movie (2020)
            #KODIPROP:inputstream.adaptive.license_type=widevine
            #KODIPROP:inputstream.adaptive.license_key=https://lic/x
            http://srv/movie/u/p/3.mp4
        """.trimIndent()
        val items = M3uParser.parse(m3u)
        val a = items.first { it.title == "Channel A" }
        val b = items.first { it.title == "Channel B" }
        val mv = items.first { it.kind == Kind.MOVIE }
        assertEquals(DrmLogic.Type.CLEARKEY, DrmLogic.of(a.url)!!.type)
        assertEquals("https://cdn.example/a/manifest.mpd", DrmLogic.base(a.url))
        assertEquals("http://srv/live/u/p/2.ts", b.url)
        assertEquals("https://lic/x", DrmLogic.of(mv.url)!!.licenseUrl)
        assertFalse(TapLogic.tappable(a.url))
    }
}
