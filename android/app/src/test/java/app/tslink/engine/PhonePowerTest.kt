package app.tslink.engine

import app.tslink.engine.PhonePower.Companion.pollStep
import app.tslink.engine.PhonePower.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class PhonePowerTest {
    @Test fun asleepWhileLitGoesDark() = assertEquals(Step.DARKEN, pollStep(interactive = false, dark = false, tick = 0))

    @Test fun powerButtonWhileDarkLightsUp() = assertEquals(Step.LIGHTEN, pollStep(interactive = false, dark = true, tick = 3))

    @Test fun awakeAndLitDoesNothing() {
        for (tick in 0..25) assertEquals(Step.NONE, pollStep(interactive = true, dark = false, tick = tick))
    }

    @Test fun awakeAndDarkReappliesEveryTenthPoll() {
        val steps = (1..30).map { pollStep(interactive = true, dark = true, tick = it) }
        assertEquals(listOf(10, 20, 30), steps.withIndex().filter { it.value == Step.REAPPLY }.map { it.index + 1 })
        assertEquals(27, steps.count { it == Step.NONE })
    }

    /** Drives pollStep the way PhonePower's poll does, through a whole drive. */
    @Test fun timeoutThenPowerButtonThenTimeout() {
        var dark = false
        var ticks = 0
        val log = ArrayList<Step>()
        fun poll(interactive: Boolean) {
            if (interactive && dark) ticks++
            val s = pollStep(interactive, dark, ticks)
            if (s == Step.DARKEN) dark = true
            if (s == Step.LIGHTEN) dark = false
            if (s != Step.NONE) log += s
        }
        poll(true) // lit, in use
        poll(false) // screen timeout: dark (darken() wakes it, so the next poll is interactive)
        repeat(10) { poll(true) } // dark: one re-assert
        poll(false) // power button: lit
        repeat(10) { poll(true) } // lit: nothing
        poll(false) // timed out again: dark
        assertEquals(listOf(Step.DARKEN, Step.REAPPLY, Step.LIGHTEN, Step.DARKEN), log)
    }

    @Test fun litLongerThanTheScreenTimeoutGoesDark() {
        // Car touches reset the phone's own timeout, so PhonePower measures it itself.
        assertEquals(Step.NONE, pollStep(interactive = true, dark = false, tick = 0, litFor = 29_999, timeout = 30_000))
        assertEquals(Step.DARKEN, pollStep(interactive = true, dark = false, tick = 0, litFor = 30_000, timeout = 30_000))
        // Dark is never re-darkened by the lit timer.
        assertEquals(Step.NONE, pollStep(interactive = true, dark = true, tick = 1, litFor = 99_999, timeout = 30_000))
    }

    @Test fun unlockedStaysLitPastTheTimeout() {
        assertEquals(Step.NONE, pollStep(interactive = true, dark = false, tick = 0, litFor = 60_000, timeout = 30_000, unlocked = true))
        assertEquals(Step.DARKEN, pollStep(interactive = true, dark = false, tick = 0, litFor = 60_000, timeout = 30_000, unlocked = false))
    }

    @Test fun unlockedButAsleepStillGoesDark() =
        assertEquals(Step.DARKEN, pollStep(interactive = false, dark = false, tick = 0, unlocked = true))

    @Test fun unlockedDoesNotChangeDarkHandling() {
        // pollStep's caller only passes unlocked for a lit phone, but dark rules stay put either way.
        assertEquals(Step.LIGHTEN, pollStep(interactive = false, dark = true, tick = 0, unlocked = true))
        assertEquals(Step.REAPPLY, pollStep(interactive = true, dark = true, tick = 10, litFor = 60_000, timeout = 30_000, unlocked = true))
        assertEquals(Step.NONE, pollStep(interactive = true, dark = true, tick = 3, unlocked = true))
    }

    @Test fun unlockedBelowTheTimeoutDoesNothing() =
        assertEquals(Step.NONE, pollStep(interactive = true, dark = false, tick = 0, litFor = 0, timeout = 30_000, unlocked = true))

    /** Unlocked on the pad for a long drive, then locked: dark after one more timeout's worth. */
    @Test fun lockingAfterALongUnlockedStretchGoesDark() {
        assertEquals(Step.NONE, pollStep(interactive = true, dark = false, tick = 0, litFor = 3_600_000, timeout = 30_000, unlocked = true))
        assertEquals(Step.DARKEN, pollStep(interactive = true, dark = false, tick = 0, litFor = 3_600_000, timeout = 30_000, unlocked = false))
    }
}
