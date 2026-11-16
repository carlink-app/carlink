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
        assertEquals("Turn on the car hotspot. In the car, join it and open tslink.app, then pair.", v.hint)
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

    @Test fun checkingShowsChecking() {
        assertEquals("Checking...", Home.view(true, EngineStatus.parse("checking...")).state)
    }

    @Test fun pendingScanOpensOnceRunning() {
        assertEquals(ScanStep(pending = false, open = true), Home.scanAfter(true, EngineStatus.parse("waiting for car · cert ab")))
    }

    @Test fun pendingScanWaitsWhileStarting() {
        assertEquals(ScanStep(pending = true, open = false), Home.scanAfter(true, EngineStatus.parse("starting...")))
    }

    @Test fun pendingScanDropsWhenStoppedOrRefused() {
        for (reply in listOf("stopped", "error: x", "Shizuku permission denied", "Shizuku isn't running. Open Shizuku and start it.")) {
            assertEquals(reply, ScanStep(pending = false, open = false), Home.scanAfter(true, EngineStatus.parse(reply)))
        }
    }

    @Test fun noScanWithoutAsking() {
        assertEquals(ScanStep(pending = false, open = false), Home.scanAfter(false, EngineStatus.parse("waiting for car · cert ab")))
    }

    @Test fun noteStaysUntilTSLinkComesUp() {
        val waiting = EngineStatus.parse("waiting for car · cert ab")
        val connected = EngineStatus.parse("car connected (9.9.0.2) · cert ab")
        assertEquals(true, Home.clearsNote(EngineStatus.Stopped, waiting))
        assertEquals(true, Home.clearsNote(EngineStatus.Busy("Starting..."), waiting))
        // Polls and the car coming and going keep "Paired with the car at ..." up.
        assertEquals(false, Home.clearsNote(waiting, connected))
        assertEquals(false, Home.clearsNote(waiting, waiting))
        assertEquals(false, Home.clearsNote(waiting, EngineStatus.Stopped))
    }

    @Test fun engineRepliesAsMessages() {
        // What reaches the note through setStatus: failures are red, refusals that need the user amber.
        assertEquals(Tone.ERROR, Home.replyTone("error: java.io.IOException: x"))
        assertEquals(Tone.ERROR, Home.replyTone("denied"))
        assertEquals(Tone.WARN, Home.replyTone("Shizuku isn't running. Open Shizuku and start it."))
        assertEquals(Tone.WARN, Home.replyTone("Shizuku permission denied"))
        assertEquals(Tone.WARN, Home.replyTone("not a TSLink pairing code"))
    }
}
