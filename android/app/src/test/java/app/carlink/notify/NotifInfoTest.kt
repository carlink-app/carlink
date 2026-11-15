package app.carlink.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifInfoTest {
    private fun decide(
        pkg: String = "com.whatsapp",
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        media: Boolean = false,
        category: String? = "msg",
        importance: Int? = 4,
        title: String? = "Alice",
        text: String? = "On my way",
    ) = NotifInfo.decide(pkg, "app.carlink", ongoing, groupSummary, media, category, importance, title, text)

    @Test fun messageIsShown() {
        assertEquals(NotifInfo("Alice", "On my way", "msg"), decide())
    }

    @Test fun ownOngoingSummaryAndMediaAreSkipped() {
        assertNull(decide(pkg = "app.carlink"))
        assertNull(decide(ongoing = true, category = "navigation"))
        assertNull(decide(groupSummary = true))
        assertNull(decide(media = true, category = "transport"))
    }

    @Test fun ongoingCallsAreShown() {
        assertEquals("call", decide(ongoing = true, category = "call", importance = 2)?.kind)
    }

    @Test fun lowImportanceIsSkipped() {
        assertNull(decide(importance = 2))
        assertEquals("msg", decide(importance = null)?.kind) // ranking unavailable: keep
    }

    @Test fun emptyIsSkippedAndLongIsTrimmed() {
        assertNull(decide(title = "  ", text = null))
        val r = decide(title = "t".repeat(500), text = "x".repeat(500))!!
        assertEquals(120, r.title.length)
        assertEquals(300, r.text.length)
    }

    @Test fun kinds() {
        assertEquals("msg", decide(category = "email")?.kind)
        assertEquals("other", decide(category = null)?.kind)
        assertEquals("other", decide(category = "promo")?.kind)
    }

    @Test fun callsBypassImportanceButNotOtherFilters() {
        // Not ongoing (ringing as a heads-up) with low importance: still shown.
        assertEquals(NotifInfo("Bob", "Incoming call", "call"), decide(category = "call", importance = 1, title = "Bob", text = "Incoming call"))
        assertEquals("call", decide(category = "call", importance = 0)?.kind)
        // Own, group-summary and media notifications are dropped even for calls.
        assertNull(decide(pkg = "app.carlink", category = "call"))
        assertNull(decide(groupSummary = true, category = "call"))
        assertNull(decide(media = true, category = "call", ongoing = true))
        // An empty call notification has nothing to show.
        assertNull(decide(category = "call", ongoing = true, title = null, text = null))
    }

    @Test fun ongoingNonCallsAreSkippedWhateverTheImportance() {
        assertNull(decide(ongoing = true, category = "msg", importance = 5))
        assertNull(decide(ongoing = true, category = null, importance = null))
    }

    @Test fun importanceBoundary() {
        // IMPORTANCE_DEFAULT (3) is the lowest that is shown.
        assertNull(decide(importance = 2))
        assertEquals("msg", decide(importance = 3)?.kind)
        assertEquals("msg", decide(importance = 5)?.kind)
        // IMPORTANCE_UNSPECIFIED (-1000) and NONE (0) are skipped.
        assertNull(decide(importance = -1000))
        assertNull(decide(importance = 0))
    }

    @Test fun nullCategoryStillPassesNonCallRules() {
        assertEquals(NotifInfo("Alice", "On my way", "other"), decide(category = null))
        assertNull(decide(category = null, importance = 2))
    }

    @Test fun whitespaceIsTrimmedAndOneFieldIsEnough() {
        assertEquals(NotifInfo("Alice", "", "msg"), decide(title = "  Alice\n", text = " \t "))
        assertEquals(NotifInfo("", "hi", "msg"), decide(title = null, text = "\nhi "))
        assertNull(decide(title = "", text = ""))
        assertNull(decide(title = "\t\n", text = "   "))
    }

    @Test fun exactLimitsAreKept() {
        val r = decide(title = "t".repeat(120), text = "x".repeat(300))!!
        assertEquals("t".repeat(120), r.title)
        assertEquals("x".repeat(300), r.text)
        val over = decide(title = "t".repeat(121), text = "x".repeat(301))!!
        assertEquals(120, over.title.length)
        assertEquals(300, over.text.length)
    }

    @Test fun trimHappensBeforeTheLimit() {
        // Leading padding doesn't eat into the 120 characters.
        val r = decide(title = " ".repeat(50) + "t".repeat(120))!!
        assertEquals("t".repeat(120), r.title)
    }

    @Test fun allKinds() {
        assertEquals("msg", decide(category = "msg")?.kind)
        assertEquals("call", decide(category = "call")?.kind)
        assertEquals("nav", decide(category = "navigation")?.kind)
        assertEquals("other", decide(category = "alarm")?.kind)
        assertEquals("other", decide(category = "")?.kind)
        // Categories are case-sensitive constants.
        assertEquals("other", decide(category = "MSG")?.kind)
    }

    @Test fun lockScreenRulesApplyInTheCar() {
        val secret = android.app.Notification.VISIBILITY_SECRET
        val private = android.app.Notification.VISIBILITY_PRIVATE
        val call = { vis: Int, override: Int?, allow: Boolean, pt: String?, px: String? ->
            NotifInfo.decide("com.bank", "app.carlink", false, false, false, "msg", 4, "Code", "482913",
                visibility = vis, lockscreenOverride = override, allowPrivate = allow, publicTitle = pt, publicText = px)
        }
        assertNull(call(secret, null, true, null, null))
        // The user's per-channel override to SECRET wins over a public notification.
        assertNull(call(1, secret, true, null, null))
        // -1000 = no override.
        assertEquals("482913", call(1, -1000, true, null, null)?.text)
        // Private content shows, as on a lock screen with sensitive notifications allowed (the default).
        assertEquals("482913", call(private, null, true, null, null)?.text)
        // Sensitive notifications off: the app's public version, or a generic line.
        assertEquals(NotifInfo("Bank", "1 new message", "msg"), call(private, null, false, "Bank", "1 new message"))
        assertEquals(NotifInfo("", "New notification", "msg"), call(private, null, false, null, null))
        // ...but public notifications keep their content.
        assertEquals("482913", call(1, null, false, null, null)?.text)
        // No notifications on the lock screen at all: no contents in the car either.
        val hidden = NotifInfo.decide("com.bank", "app.carlink", false, false, false, "msg", 4, "Code", "482913",
            hideContent = true)
        assertEquals("New notification", hidden?.text)
    }

    @Test fun takeCharsNeverSplitsEmoji() {
        val s = "ab" + "\uD83D\uDE00" // a, b, grinning face (2 UTF-16 units)
        assertEquals("ab", s.takeChars(3))
        assertEquals(s, s.takeChars(4))
        assertEquals("a", "a".takeChars(5))
    }
}
