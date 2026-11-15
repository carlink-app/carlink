package app.carlink.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchClockTest {
    @Test fun keepsTheCarsSpacingWhenMovesArriveInABurst() {
        val c = TouchClock()
        assertEquals(1_000L, c.eventTime(500.0, 1_000, gestureStart = true))
        // Moves made 16 ms apart in the car, all arriving at once 60 ms later.
        assertEquals(1_016L, c.eventTime(516.0, 1_060, gestureStart = false))
        assertEquals(1_032L, c.eventTime(532.0, 1_060, gestureStart = false))
        assertEquals(1_048L, c.eventTime(548.0, 1_060, gestureStart = false))
    }

    @Test fun neverAheadOfNowNorBackwards() {
        val c = TouchClock()
        c.eventTime(0.0, 1_000, gestureStart = true)
        // Car clock runs ahead of the phone's (or the anchor was late): capped at now.
        assertEquals(1_005L, c.eventTime(50.0, 1_005, gestureStart = false))
        // An older timestamp arriving later: not before the last event.
        assertEquals(1_005L, c.eventTime(2.0, 1_010, gestureStart = false))
    }

    @Test fun reAnchorsAfterAHiccup() {
        val c = TouchClock()
        c.eventTime(0.0, 1_000, gestureStart = true)
        // 500 ms network stall: follow the phone's clock again.
        assertEquals(1_516L, c.eventTime(16.0, 1_516, gestureStart = false))
        assertEquals(1_532L, c.eventTime(32.0, 1_540, gestureStart = false))
    }

    @Test fun olderPagesWithoutTimestampsUseArrivalTime() {
        val c = TouchClock()
        assertEquals(1_234L, c.eventTime(Double.NaN, 1_234, gestureStart = true))
    }
}
