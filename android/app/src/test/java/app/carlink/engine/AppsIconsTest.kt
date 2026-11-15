package app.carlink.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsIconsTest {
    private val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"

    @Test fun iconHashIsStable() {
        assertEquals(Apps.iconHash(png), Apps.iconHash(String(png.toCharArray())))
    }

    @Test fun iconHashFormatIsHexDashLength() {
        val h = Apps.iconHash(png)
        assertTrue(h, Regex("^[0-9a-f]{1,8}-[0-9]+$").matches(h))
        assertTrue(h.endsWith("-${png.length}"))
        assertEquals(Integer.toHexString(png.hashCode()) + "-" + png.length, h)
        assertEquals("0-0", Apps.iconHash(""))
    }

    @Test fun iconHashChangesWithTheIcon() {
        assertNotEquals(Apps.iconHash(png), Apps.iconHash(png + "A"))
        assertNotEquals(Apps.iconHash(png), Apps.iconHash(png.replaceFirst('i', 'j')))
        // Same String.hashCode, different length: the length tells them apart.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(Apps.iconHash("Aa"), Apps.iconHash("Aa\u0000"))
    }

    private fun app(pkg: String, ih: String? = null) = JSONObject().put("pkg", pkg).put("cls", "$pkg.Main").put("label", pkg).also { if (ih != null) it.put("ih", ih) }

    @Test fun withIconsPutsIconsInlineAndKeepsTheRest() {
        val json = JSONArray().put(app("a", "h1")).put(app("b")).put(app("c", "h3"))
        val out = Apps.withIcons(json, mapOf("a" to png, "c" to "data:image/png;base64,CC"))
        assertEquals(3, out.length())
        assertEquals(png, out.getJSONObject(0).getString("icon"))
        assertEquals("h1", out.getJSONObject(0).getString("ih"))
        assertEquals("a.Main", out.getJSONObject(0).getString("cls"))
        assertFalse(out.getJSONObject(1).has("icon"))
        assertEquals("data:image/png;base64,CC", out.getJSONObject(2).getString("icon"))
        assertEquals(listOf("a", "b", "c"), (0 until out.length()).map { out.getJSONObject(it).getString("pkg") })
    }

    @Test fun withIconsLeavesTheCachedListingUntouched() {
        val json = JSONArray().put(app("a", "h1"))
        val before = json.toString()
        Apps.withIcons(json, mapOf("a" to png))
        assertEquals(before, json.toString())
    }

    @Test fun withIconsEmpty() {
        assertEquals(0, Apps.withIcons(JSONArray(), mapOf("a" to png)).length())
        assertEquals(JSONArray().put(app("a")).toString(), Apps.withIcons(JSONArray().put(app("a")), emptyMap()).toString())
    }
}
