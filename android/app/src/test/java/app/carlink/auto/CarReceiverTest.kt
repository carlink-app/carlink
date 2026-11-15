package app.carlink.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class CarReceiverTest {
    private val car = "AA:BB:CC:DD:EE:FF"
    private val on = "android.bluetooth.device.action.ACL_CONNECTED"
    private val off = "android.bluetooth.device.action.ACL_DISCONNECTED"

    @Test fun carConnectStartsAndDisconnectStops() {
        assertEquals(CarReceiver.Action.START, decide(on, car, car))
        assertEquals(CarReceiver.Action.STOP, decide(off, car, car))
        assertEquals(CarReceiver.Action.START, decide(on, car.lowercase(), car))
    }

    @Test fun otherDevicesAndNoCarDoNothing() {
        assertEquals(CarReceiver.Action.NONE, decide(on, "11:22:33:44:55:66", car))
        assertEquals(CarReceiver.Action.NONE, decide(on, car, null))
        assertEquals(CarReceiver.Action.NONE, decide(on, null, car))
        assertEquals(CarReceiver.Action.NONE, decide("android.intent.action.BOOT_COMPLETED", car, car))
    }

    @Test fun disconnectMatchesCaseInsensitivelyToo() {
        assertEquals(CarReceiver.Action.STOP, decide(off, car.lowercase(), car))
        assertEquals(CarReceiver.Action.STOP, decide(off, car, car.lowercase()))
    }

    @Test fun nullOrUnrelatedActionsDoNothing() {
        assertEquals(CarReceiver.Action.NONE, decide(null, car, car))
        assertEquals(CarReceiver.Action.NONE, decide("android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED", car, car))
        assertEquals(CarReceiver.Action.NONE, decide(off, "11:22:33:44:55:66", car))
        assertEquals(CarReceiver.Action.NONE, decide(off, null, car))
        assertEquals(CarReceiver.Action.NONE, decide(off, car, null))
        // Prefix of the car's address isn't the car.
        assertEquals(CarReceiver.Action.NONE, decide(on, "AA:BB:CC:DD:EE", car))
    }
}

class CarStopJobTest {
    @Test fun stopsOnlyAutoStartedAndNotAfterReconnect() {
        org.junit.Assert.assertTrue(CarStopJob.shouldStop(autoStarted = true, lastConnect = 100, disconnectedAt = 200))
        org.junit.Assert.assertFalse(CarStopJob.shouldStop(autoStarted = true, lastConnect = 300, disconnectedAt = 200))
        org.junit.Assert.assertFalse(CarStopJob.shouldStop(autoStarted = false, lastConnect = 100, disconnectedAt = 200))
    }
}
