package app.carlink.ui

import app.carlink.ui.EngineStatus.Busy
import app.carlink.ui.EngineStatus.Message
import app.carlink.ui.EngineStatus.Running
import app.carlink.ui.EngineStatus.Stopped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStatusTest {
    private fun parse(s: String) = EngineStatus.parse(s)

    @Test fun stopped() {
        assertEquals(Stopped, parse("stopped"))
        assertEquals(Stopped, parse("engine stopped"))
        assertEquals(Stopped, parse("stopped (by car)"))
    }

    @Test fun waitingWithCert() {
        assertEquals(Running("Waiting for car", "ab12cd", connected = false), parse("waiting for car · cert ab12cd"))
    }

    @Test fun connectedWithCert() {
        assertEquals(
            Running("Car connected (192.168.4.2:5000)", "ab12cd", connected = true),
            parse("car connected (192.168.4.2:5000) · cert ab12cd"),
        )
    }

    @Test fun runningWithoutCert() {
        assertEquals(Running("Waiting for car", null, connected = false), parse("waiting for car"))
        assertEquals(Running("Running on 8443", null, connected = false), parse("running on 8443"))
        assertEquals(Running("Already running", null, connected = false), parse("already running"))
        // Empty cert after the separator counts as none.
        assertEquals(Running("Waiting for car", null, connected = false), parse("waiting for car · cert "))
    }

    @Test fun busy() {
        assertEquals(Busy("Starting engine..."), parse("starting engine..."))
        assertEquals(Busy("Checking engine..."), parse("checking engine..."))
    }

    @Test fun everythingElseIsAMessage() {
        assertEquals(Message("error: boom"), parse("error: boom"))
        assertEquals(Message("denied"), parse("denied"))
        assertEquals(Message("Shizuku isn't running."), parse("Shizuku isn't running."))
        assertEquals(Message(""), parse(""))
        // Only exact / prefix matches are states.
        assertEquals(Message("not stopped"), parse("not stopped"))
        assertEquals(Message("already running twice"), parse("already running twice"))
    }

    @Test fun errors() {
        assertTrue(EngineStatus.isError("error: x"))
        assertTrue(EngineStatus.isError("denied"))
        assertFalse(EngineStatus.isError("denied?"))
        assertFalse(EngineStatus.isError("ok"))
    }
}
