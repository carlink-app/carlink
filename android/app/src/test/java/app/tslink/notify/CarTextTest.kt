package app.tslink.notify

import android.app.Notification
import app.tslink.Prefs
import app.tslink.Prefs.CarText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarTextTest {
    private fun rule(mode: CarText, sensitive: Boolean, onLockscreen: Boolean) = CarTextRule.of(mode, { sensitive }, { onLockscreen })

    /** What Listener sends for a private bank notification under [mode] and the lock-screen settings. */
    private fun show(mode: CarText, sensitive: Boolean = true, onLockscreen: Boolean = true, visibility: Int = Notification.VISIBILITY_PRIVATE): NotifInfo? {
        val r = rule(mode, sensitive, onLockscreen)
        return NotifInfo.decide(
            "com.bank", "app.tslink", false, false, false, "msg", 4, "Code", "482913",
            visibility = visibility, allowPrivate = r.allowPrivate, hideContent = r.hideContent,
            publicTitle = "Bank", publicText = "1 new message",
        )?.let { if (r.stripText) it.withoutText() else it }
    }

    @Test fun lockScreenModeFollowsTheLockScreen() {
        assertEquals(CarTextRule(allowPrivate = true, hideContent = false, stripText = false), rule(CarText.LOCK_SCREEN, true, true))
        assertEquals(CarTextRule(allowPrivate = false, hideContent = false, stripText = false), rule(CarText.LOCK_SCREEN, false, true))
        assertEquals(CarTextRule(allowPrivate = true, hideContent = true, stripText = false), rule(CarText.LOCK_SCREEN, true, false))
        assertEquals(NotifInfo("Code", "482913", "msg"), show(CarText.LOCK_SCREEN))
        assertEquals(NotifInfo("Bank", "1 new message", "msg"), show(CarText.LOCK_SCREEN, sensitive = false))
        assertEquals(NotifInfo("Bank", "1 new message", "msg"), show(CarText.LOCK_SCREEN, onLockscreen = false))
    }

    @Test fun alwaysIgnoresTheLockScreenAndDoesNotReadIt() {
        val r = CarTextRule.of(CarText.ALWAYS, { error("read sensitive") }, { error("read lock screen") })
        assertEquals(CarTextRule(allowPrivate = true, hideContent = false, stripText = false), r)
        assertEquals(NotifInfo("Code", "482913", "msg"), show(CarText.ALWAYS, sensitive = false, onLockscreen = false))
    }

    @Test fun alwaysStillHonoursSecret() {
        // Secret notifications never reach the car, whatever the setting.
        assertNull(show(CarText.ALWAYS, visibility = Notification.VISIBILITY_SECRET))
    }

    @Test fun neverKeepsTheSenderButDropsTheMessage() {
        assertEquals(CarTextRule(allowPrivate = true, hideContent = false, stripText = true), rule(CarText.NEVER, true, true))
        assertEquals(NotifInfo("Code", "", "msg"), show(CarText.NEVER))
        // Still the lock screen's rules for the title.
        assertEquals(NotifInfo("Bank", "", "msg"), show(CarText.NEVER, sensitive = false))
        assertEquals(NotifInfo("Bank", "", "msg"), show(CarText.NEVER, onLockscreen = false))
        assertNull(show(CarText.NEVER, visibility = Notification.VISIBILITY_SECRET))
    }

    @Test fun withoutTextKeepsTitleAndKind() {
        assertEquals(NotifInfo("Alice", "", "call"), NotifInfo("Alice", "Incoming", "call").withoutText())
        assertEquals(NotifInfo("", "", "other"), NotifInfo("", "New notification", "other").withoutText())
    }

    @Test fun storedModeParsing() {
        assertEquals(CarText.LOCK_SCREEN, Prefs.parseCarText(null))
        assertEquals(CarText.LOCK_SCREEN, Prefs.parseCarText(""))
        assertEquals(CarText.LOCK_SCREEN, Prefs.parseCarText("SOMETIMES")) // a newer version's value
        assertEquals(CarText.LOCK_SCREEN, Prefs.parseCarText("always")) // names are exact
        for (m in CarText.values()) assertEquals(m, Prefs.parseCarText(m.name))
    }

    @Test fun segmentOrderMatchesTheSettingsScreen() {
        // MainActivity's segmented control is Default, Always, Never by ordinal.
        assertEquals(listOf(CarText.LOCK_SCREEN, CarText.ALWAYS, CarText.NEVER), CarText.values().toList())
    }
}
