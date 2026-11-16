package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {
    private val hevc = "video/hevc"
    private val avc = "video/avc"

    @Test fun hardwareFirst() {
        val list = listOf(
            Codecs.Encoder("c2.android.hevc.encoder", false, listOf(hevc)),
            Codecs.Encoder("c2.exynos.hevc.encoder", true, listOf(hevc)),
            Codecs.Encoder("c2.exynos.h264.encoder", true, listOf(avc)),
        )
        assertEquals(listOf("c2.exynos.hevc.encoder", "c2.android.hevc.encoder"), Codecs.ranked(list, hevc).map { it.name })
        assertEquals(listOf("c2.exynos.h264.encoder"), Codecs.ranked(list, "VIDEO/AVC").map { it.name })
    }

    @Test fun platformOrderAmongEquals() {
        val list = listOf(Codecs.Encoder("a", true, listOf(avc)), Codecs.Encoder("b", true, listOf(avc)))
        assertEquals(listOf("a", "b"), Codecs.ranked(list, avc).map { it.name })
    }

    @Test fun softwareNames() {
        assertTrue(Codecs.softwareName("c2.android.avc.encoder"))
        assertTrue(Codecs.softwareName("OMX.google.h264.encoder"))
        assertFalse(Codecs.softwareName("c2.qti.avc.encoder"))
    }
}
