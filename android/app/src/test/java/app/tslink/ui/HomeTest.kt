package app.tslink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTest {
    private val waiting = EngineStatus.Running("Waiting for car", "ab", false)
    private val connected = EngineStatus.Running("Car connected (9.9.0.2)", "ab", true)

    @Test fun setupMissingMeansFinishSetup() {
        val v = Home.view(false, EngineStatus.Stopped)
        assertEquals(Primary.FINISH_SETUP, v.primary)
        assertEquals("Finish setup to use TSLink", v.state)
    }

    @Test fun shizukuGoneMeansSetup() {
        assertEquals(Primary.FINISH_SETUP, Home.view(false, waiting).primary)
    }

    @Test fun offOffersPair() {
        val v = Home.view(true, EngineStatus.Stopped)
        assertEquals("Off", v.state)
        assertEquals(Primary.PAIR, v.primary)
        assertEquals(HomeColor.MUTED, v.color)
    }

    @Test fun readyOffersPair() {
        val v = Home.view(true, waiting)
        assertEquals("Ready: waiting for the car", v.state)
        assertEquals("In the car: join this phone's hotspot and open tslink.app", v.hint)
        assertEquals(Primary.PAIR, v.primary)
        assertEquals(HomeColor.WARN, v.color)
    }

    @Test fun connectedOffersDisconnect() {
        val v = Home.view(true, connected)
        assertEquals("Connected to the car", v.state)
        assertNull(v.hint)
        assertEquals(Primary.DISCONNECT, v.primary)
        assertEquals(HomeColor.OK, v.color)
    }

    @Test fun busyShowsStarting() {
        val v = Home.view(true, EngineStatus.Busy("Starting engine..."))
        assertEquals("Starting...", v.state)
        assertEquals(Primary.PAIR, v.primary)
    }

    @Test fun messageCountsAsOff() {
        assertEquals("Off", Home.view(true, EngineStatus.Message("error: x")).state)
    }

    @Test fun noEngineWordAnywhere() {
        val all = listOf(false, true).flatMap { ready ->
            listOf(EngineStatus.Stopped, waiting, connected, EngineStatus.Busy("Starting engine...")).map { Home.view(ready, it) }
        }
        for (v in all) assertFalse(v.toString(), "engine" in (v.state + v.hint).lowercase())
    }
}
