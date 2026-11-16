package app.tslink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test fun noMatchingEncoder() {
        val list = listOf(Codecs.Encoder("c2.qti.avc.encoder", true, listOf(avc)))
        assertTrue(Codecs.ranked(list, hevc).isEmpty())
        assertTrue(Codecs.ranked(emptyList(), avc).isEmpty())
    }

    @Test fun multiTypeEncoderAndSoftwareOnly() {
        val list = listOf(
            Codecs.Encoder("OMX.google.h264.encoder", false, listOf(avc)),
            Codecs.Encoder("c2.mtk.video.encoder", true, listOf(hevc, avc)),
        )
        assertEquals(listOf("c2.mtk.video.encoder", "OMX.google.h264.encoder"), Codecs.ranked(list, avc).map { it.name })
        // Software only: still ranked (hasEncoder decides whether HEVC is offered).
        assertEquals(listOf("OMX.google.h264.encoder"), Codecs.ranked(list.take(1), avc).map { it.name })
    }

    @Test fun softwareNamesArePrefixesAndCaseSensitive() {
        assertFalse(Codecs.softwareName("vendor.c2.android.avc.encoder"))
        assertFalse(Codecs.softwareName("omx.google.h264.encoder"))
        assertFalse(Codecs.softwareName("OMX.qcom.video.encoder.avc"))
    }

    @Test fun formatLevelsAddKeys() {
        assertTrue(Codecs.optionalKeys(0).isEmpty())
        assertEquals(setOf("repeat-previous-frame-after"), Codecs.optionalKeys(1).keys)
        assertEquals(setOf("repeat-previous-frame-after", "max-bframes", "prepend-sps-pps-to-idr-frames"), Codecs.optionalKeys(2).keys)
        val top = Codecs.optionalKeys(Codecs.TOP_LEVEL)
        assertEquals(setOf("repeat-previous-frame-after", "max-bframes", "prepend-sps-pps-to-idr-frames", "priority", "latency", "operating-rate"), top.keys)
        for (l in 1..Codecs.TOP_LEVEL) assertTrue(Codecs.optionalKeys(l).entries.containsAll(Codecs.optionalKeys(l - 1).entries))
        // Above the top level: nothing more.
        assertEquals(top, Codecs.optionalKeys(Codecs.TOP_LEVEL + 1))
    }

    @Test fun formatValues() {
        val top = Codecs.optionalKeys(Codecs.TOP_LEVEL)
        // The repeat interval is a long (setLong); the rest are ints (setInteger).
        assertEquals(100_000L, top["repeat-previous-frame-after"])
        assertEquals(0, top["max-bframes"])
        assertEquals(1, top["prepend-sps-pps-to-idr-frames"])
        assertEquals(0, top["priority"])
        assertEquals(1, top["latency"])
        assertEquals(Short.MAX_VALUE.toInt(), top["operating-rate"])
        assertTrue(top.filterKeys { it != "repeat-previous-frame-after" }.values.all { it is Int })
    }

    private class Run(private val refuseAbove: Int, private val error: (Int) -> Exception = { IllegalArgumentException("level $it") }) {
        val tried = ArrayList<Int>()
        val refused = ArrayList<Int>()
        var resets = 0
        fun go(top: Int = Codecs.TOP_LEVEL) = Codecs.firstAccepted(
            top,
            retryable = { it is IllegalArgumentException || it is IllegalStateException },
            attempt = { tried += it; if (it > refuseAbove) throw error(it) },
            refused = { l, _ -> refused += l },
            reset = { resets++ },
        )
    }

    @Test fun topLevelAcceptedFirstTry() {
        val r = Run(refuseAbove = 99)
        assertEquals(3, r.go())
        assertEquals(listOf(3), r.tried)
        assertEquals(0, r.resets)
    }

    @Test fun fallsBackLevelByLevelWithResets() {
        val r = Run(refuseAbove = 1)
        assertEquals(1, r.go())
        assertEquals(listOf(3, 2, 1), r.tried)
        assertEquals(listOf(3, 2), r.refused)
        assertEquals(2, r.resets)
    }

    @Test fun bareMinimumRefusedIsRethrownWithoutReset() {
        val r = Run(refuseAbove = -1)
        val e = assertThrows(IllegalArgumentException::class.java) { r.go() }
        assertEquals("level 0", e.message)
        assertEquals(listOf(3, 2, 1, 0), r.tried)
        assertEquals(listOf(3, 2, 1, 0), r.refused)
        assertEquals(3, r.resets) // not after level 0
    }

    @Test fun otherErrorsAreNotRetried() {
        val r = Run(refuseAbove = 0) { RuntimeException("dead codec") }
        assertThrows(RuntimeException::class.java) { r.go() }
        assertEquals(listOf(3), r.tried)
        assertTrue(r.refused.isEmpty())
        assertEquals(0, r.resets)
    }

    @Test fun noLevels() {
        assertThrows(IllegalArgumentException::class.java) { Run(refuseAbove = 99).go(top = -1) }
    }
}
