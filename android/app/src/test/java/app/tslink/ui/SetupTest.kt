package app.tslink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val fresh = SetupFacts(
        shizukuInstalled = false,
        shizukuRunning = false,
        shizukuAllowed = null,
        notificationPermission = false,
        carNotifications = false,
        batteryUnrestricted = false,
        carChosen = false,
        brand = Brand.OTHER,
        ticked = emptySet(),
    )
    private val ready = fresh.copy(
        shizukuInstalled = true,
        shizukuRunning = true,
        shizukuAllowed = true,
        notificationPermission = true,
        carNotifications = true,
        batteryUnrestricted = true,
        carChosen = true,
        ticked = setOf(StepId.HOTSPOT),
    )

    private fun steps(f: SetupFacts) = Setup.steps(f)
    private fun step(f: SetupFacts, id: StepId) = steps(f).single { it.id == id }
    private fun state(f: SetupFacts, id: StepId) = step(f, id).state

    @Test fun freshPhone() {
        val s = steps(fresh)
        assertEquals(StepState.ACTION, state(fresh, StepId.SHIZUKU_INSTALLED))
        assertEquals(StepState.ACTION, state(fresh, StepId.SHIZUKU_RUNNING))
        assertEquals(StepState.UNKNOWN, state(fresh, StepId.SHIZUKU_ALLOWED))
        assertEquals(StepState.UNKNOWN, state(fresh, StepId.HOTSPOT))
        assertEquals("0 of 5 ready · optional 0 of 3", Setup.summary(s))
        assertFalse(Setup.allRequiredReady(s))
    }

    @Test fun stepOrderRequiredFirst() {
        assertEquals(
            listOf(
                StepId.SHIZUKU_INSTALLED, StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_ALLOWED, StepId.BATTERY, StepId.HOTSPOT,
                StepId.NOTIFICATION_PERMISSION, StepId.CAR_NOTIFICATIONS, StepId.CAR,
            ),
            steps(fresh).map { it.id },
        )
        assertEquals(setOf(StepId.NOTIFICATION_PERMISSION, StepId.CAR_NOTIFICATIONS, StepId.CAR), steps(fresh).filter { it.optional }.map { it.id }.toSet())
    }

    @Test fun shizukuBlocksLaterSteps() {
        // Not installed: can't open it.
        assertFalse(step(fresh, StepId.SHIZUKU_RUNNING).actionEnabled)
        // Installed but stopped: open it; permission still unknown and not askable.
        val installed = fresh.copy(shizukuInstalled = true)
        assertTrue(step(installed, StepId.SHIZUKU_RUNNING).actionEnabled)
        assertFalse(step(installed, StepId.SHIZUKU_ALLOWED).actionEnabled)
        assertEquals(StepState.UNKNOWN, state(installed, StepId.SHIZUKU_ALLOWED))
        // Running, not allowed: ask.
        val running = installed.copy(shizukuRunning = true, shizukuAllowed = false)
        assertEquals(StepState.OK, state(running, StepId.SHIZUKU_RUNNING))
        assertEquals(StepState.ACTION, state(running, StepId.SHIZUKU_ALLOWED))
        assertTrue(step(running, StepId.SHIZUKU_ALLOWED).actionEnabled)
        assertEquals("Allow TSLink", step(running, StepId.SHIZUKU_ALLOWED).action)
    }

    @Test fun doneStepsHaveNoAction() {
        for (id in listOf(StepId.SHIZUKU_INSTALLED, StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_ALLOWED, StepId.BATTERY, StepId.NOTIFICATION_PERMISSION)) {
            assertNull(id.name, step(ready, id).action)
        }
        // Still useful once done: change the car, turn access off, re-check a ticked setting.
        assertEquals("Change car", step(ready, StepId.CAR).action)
        assertEquals("Choose car", step(fresh, StepId.CAR).action)
        assertTrue(step(ready, StepId.CAR_NOTIFICATIONS).action != null)
        assertTrue(step(ready, StepId.HOTSPOT).action != null)
    }

    @Test fun allReady() {
        val s = steps(ready)
        assertTrue(s.all { it.state == StepState.OK })
        assertEquals("All set: 5 of 5 ready · optional 3 of 3", Setup.summary(s))
        assertTrue(Setup.allRequiredReady(s))
    }

    @Test fun optionalStepsDontBlockReady() {
        val s = steps(ready.copy(notificationPermission = false, carNotifications = false, carChosen = false))
        assertTrue(Setup.allRequiredReady(s))
        assertEquals("All set: 5 of 5 ready · optional 0 of 3", Setup.summary(s))
    }

    @Test fun partlyReady() {
        val f = fresh.copy(shizukuInstalled = true, shizukuRunning = true, shizukuAllowed = false, batteryUnrestricted = true)
        assertEquals("3 of 5 ready · optional 0 of 3", Setup.summary(steps(f)))
    }

    @Test fun tickedHotspot() {
        assertEquals(StepState.OK, state(fresh.copy(ticked = setOf(StepId.HOTSPOT)), StepId.HOTSPOT))
        // A tick for a step this phone doesn't show changes nothing.
        assertEquals(steps(fresh), steps(fresh.copy(ticked = setOf(StepId.SAMSUNG_SLEEP))))
    }

    @Test fun brandSteps() {
        fun ids(b: Brand) = steps(fresh.copy(brand = b)).map { it.id }
        val brandIds = setOf(StepId.XIAOMI_USB_SECURITY, StepId.XIAOMI_AUTOSTART, StepId.SAMSUNG_SLEEP, StepId.OPPO_BACKGROUND)
        assertTrue(ids(Brand.OTHER).none { it in brandIds })
        assertEquals(listOf(StepId.XIAOMI_USB_SECURITY, StepId.XIAOMI_AUTOSTART), ids(Brand.XIAOMI).filter { it in brandIds })
        assertEquals(listOf(StepId.SAMSUNG_SLEEP), ids(Brand.SAMSUNG).filter { it in brandIds })
        assertEquals(listOf(StepId.OPPO_BACKGROUND), ids(Brand.OPPO).filter { it in brandIds })
        // Brand steps are required, ticked by the user.
        val xiaomi = steps(fresh.copy(brand = Brand.XIAOMI))
        assertTrue(xiaomi.filter { it.id in brandIds }.all { it.tickable && !it.optional && it.state == StepState.UNKNOWN })
        assertEquals("0 of 7 ready · optional 0 of 3", Setup.summary(xiaomi))
    }

    @Test fun brandDetection() {
        assertEquals(Brand.XIAOMI, Brand.of("Xiaomi", "Redmi"))
        assertEquals(Brand.XIAOMI, Brand.of("Xiaomi", "POCO"))
        assertEquals(Brand.XIAOMI, Brand.of("XIAOMI", "xiaomi"))
        assertEquals(Brand.XIAOMI, Brand.of("unknown", "poco"))
        assertEquals(Brand.SAMSUNG, Brand.of("samsung", "samsung"))
        assertEquals(Brand.OPPO, Brand.of("OnePlus", "OnePlus"))
        assertEquals(Brand.OPPO, Brand.of("OPPO", "OPPO"))
        assertEquals(Brand.OPPO, Brand.of("realme", "realme"))
        assertEquals(Brand.OTHER, Brand.of("Google", "google"))
        assertEquals(Brand.OTHER, Brand.of("", ""))
    }

    @Test fun stateLabels() {
        assertEquals("Ready", Setup.stateLabel(step(ready, StepId.BATTERY)))
        assertEquals("Done", Setup.stateLabel(step(ready, StepId.HOTSPOT)))
        assertEquals("Needs action", Setup.stateLabel(step(fresh, StepId.BATTERY)))
        assertEquals("Not set up (optional)", Setup.stateLabel(step(fresh, StepId.CAR)))
        assertEquals("Can't check yet", Setup.stateLabel(step(fresh, StepId.SHIZUKU_ALLOWED)))
        assertEquals("Check it, then mark it done", Setup.stateLabel(step(fresh, StepId.HOTSPOT)))
    }
}
