package app.carlink.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneFitTest {
    private val upTo1920 = { w: Int, h: Int -> w <= 1920 && h <= 1920 }

    @Test fun keepsSupportedSize() {
        assertEquals(1904 to 1200, Pane.fit(1904, 1200, 2, 2, upTo1920))
    }

    @Test fun scalesDownKeepingAspect() {
        // 2x-DPR window from QA: 3648x1810 doesn't fit a 1920-wide encoder.
        val (w, h) = Pane.fit(3648, 1810, 2, 2, upTo1920)
        assertEquals(1824 to 904, w to h)
    }

    @Test fun respectsAlignment() {
        val (w, h) = Pane.fit(1000, 750, 16, 16) { _, _ -> true }
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(992 to 736, w to h)
    }

    @Test(expected = IllegalArgumentException::class)
    fun failsWhenNothingFits() {
        Pane.fit(1000, 1000, 2, 2) { _, _ -> false }
    }

    @Test fun alignmentBelowTwoStillGivesEvenSizes() {
        // Some encoders report alignment 1; odd sizes still break 4:2:0.
        assertEquals(1000 to 600, Pane.fit(1001, 601, 1, 0) { _, _ -> true })
    }

    @Test fun neverAsksForAZeroSize() {
        val asked = mutableListOf<Pair<Int, Int>>()
        runCatching { Pane.fit(20, 20, 16, 16) { w, h -> asked += w to h; false } }
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { (w, h) -> w > 0 && h > 0 })
    }

    @Test fun triesFivePercentStepsDownToTenPercent() {
        val asked = mutableListOf<Int>()
        runCatching { Pane.fit(2000, 1000, 2, 2) { w, _ -> asked += w; false } }
        assertEquals((20 downTo 2).map { 2000 * it / 20 }, asked)
    }

    @Test fun picksTheLargestSupportedStep() {
        // Height limit only: aspect is kept, so the width shrinks with it.
        assertEquals(1400 to 700, Pane.fit(2000, 1000, 2, 2) { _, h -> h <= 700 })
    }

    @Test fun maxBitrateHevcIsLowerThanH264() {
        assertEquals(15_000_000, Pane.maxBitrate("h264"))
        assertEquals(9_000_000, Pane.maxBitrate("hevc"))
        assertEquals(15_000_000, Pane.maxBitrate("anything-else"))
    }

    @Test fun scaledDpiKeepsDpSize() {
        assertEquals(200, Pane.scaledDpi(200, 1824, 1824))
        // 3648 -> 2006 px at 518 dpi: 2006 px / (285 dpi / 160) = 1126 dp, as 3648 px at 518 dpi.
        assertEquals(284, Pane.scaledDpi(518, 3648, 2006))
        assertEquals(72, Pane.scaledDpi(100, 4000, 1000))
    }
}
