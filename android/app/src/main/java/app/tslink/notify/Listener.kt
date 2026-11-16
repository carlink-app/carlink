package app.tslink.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.tslink.Prefs
import app.tslink.engine.pngDataUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Forwards phone notifications worth a glance to the car, and supplies app
 * icons (the engine's shell process can't render adaptive icons). Notification
 * access is granted by the user in Android's settings.
 */
class Listener : NotificationListenerService() {
    companion object {
        /** Launcher icons; notification banners use smaller ones (16 KB limit in the protocol). */
        private const val ICON_PX = 72
        private const val NOTIF_ICON_PX = 48
        private const val MAX_TRACKED = 50
        /** Icons per binder call: one-way calls share a ~512 KB buffer. */
        private const val ICONS_PER_CALL = 8
    }

    private var link: EngineLink? = null
    private var media: MediaWatch? = null
    /** Car-side id -> notification, for "open" requests. */
    private val tracked = object : LinkedHashMap<String, StatusBarNotification>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StatusBarNotification>) = size > MAX_TRACKED
    }
    /** Ids the car was sent, so every one gets its notif-gone (tracked forgets old ones). */
    private val sent = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= 1000) remove(first())
            return super.add(element)
        }
    }

    override fun onListenerConnected() {
        link = EngineLink(this, onConnected = ::sendAll, onMessage = ::fromEngine)
        media = MediaWatch(this, ComponentName(this, Listener::class.java), ::appLabel) { link?.send(it) }.also { it.watch() }
        Prefs.shared(this).registerOnSharedPreferenceChangeListener(carTextChanged)
    }

    /** The car text setting changed: send what's on the phone again with the new rule. */
    private val carTextChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.CAR_TEXT_KEY) resync()
    }

    override fun onListenerDisconnected() {
        Prefs.shared(this).unregisterOnSharedPreferenceChangeListener(carTextChanged)
        // Access turned off: the car must not keep showing what it had.
        link?.send(JSONObject().put("t", "notif-reset"))
        link?.send(JSONObject().put("t", "media").put("none", true))
        media?.stop()
        media = null
        CarSessionNotice.update(this, false)
        link?.close()
        link = null
    }

    override fun onDestroy() {
        media?.stop()
        link?.close()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) = post(sbn, rankingMap, quiet = false)

    /** [quiet]: already on the phone before the link (re-sync): track it, don't pop it up in the car. */
    private fun post(sbn: StatusBarNotification, rankingMap: RankingMap, quiet: Boolean) {
        val l = link ?: return
        val ranking = Ranking().takeIf { rankingMap.getRanking(sbn.key, it) }
        val id = idFor(sbn.key)
        val override = if (Build.VERSION.SDK_INT >= 31) ranking?.lockscreenVisibilityOverride else null
        val rule = CarTextRule.of(Prefs.carText(this), ::sensitiveOnLockscreen, ::notificationsOnLockscreen)
        val info = NotifInfo.of(
            sbn, packageName, ranking?.importance, override,
            allowPrivate = rule.allowPrivate,
            hideContent = rule.hideContent,
        )?.let { if (rule.stripText) it.withoutText() else it }
        if (info == null) {
            // An update can turn a shown notification into one we don't show (silent, ongoing).
            forget(id)
            return
        }
        tracked[id] = sbn
        sent.add(id)
        l.send(
            JSONObject().put("t", "notif").put("id", id).put("pkg", sbn.packageName)
                .put("app", appLabel(sbn.packageName)).put("title", info.title).put("text", info.text)
                .put("kind", info.kind).put("icon", appIcon(sbn.packageName, NOTIF_ICON_PX) ?: "")
                .put("quiet", quiet),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = forget(idFor(sbn.key))

    private fun forget(id: String) {
        tracked.remove(id)
        if (sent.remove(id)) link?.send(JSONObject().put("t", "notif-gone").put("id", id))
    }

    /** The user's lock-screen "sensitive notifications" setting (default: shown). */
    private fun sensitiveOnLockscreen(): Boolean =
        Settings.Secure.getInt(contentResolver, "lock_screen_allow_private_notifications", 1) != 0

    /** Whether the lock screen shows notifications at all (if not: car shows no contents). */
    private fun notificationsOnLockscreen(): Boolean =
        Settings.Secure.getInt(contentResolver, "lock_screen_show_notifications", 1) != 0

    /** On (re)link the engine may hold stale ones: replace its view with what's on the phone now. */
    private fun resync() {
        val l = link ?: return
        tracked.clear()
        sent.clear()
        l.send(JSONObject().put("t", "notif-reset"))
        val all = try { activeNotifications } catch (e: Exception) { emptyArray() }
        val ranking = currentRanking
        for (sbn in all) post(sbn, ranking, quiet = true)
    }

    /** Everything the engine shows: notifications, icons and what plays. */
    private fun sendAll() {
        resync()
        sendIcons()
        media?.resend()
    }

    /** Engine asks to open a notification on the car display. */
    private fun fromEngine(m: JSONObject) {
        if (m.optString("t") == "session") return CarSessionNotice.update(this, m.optBoolean("up"))
        // A new engine (started, or back from pairing) has nothing: send it all again.
        if (m.optString("t") == "resync") return mainExecutor.execute(::sendAll)
        // Binder thread: media controls belong on the main thread with the watcher.
        if (m.optString("t") == "media-cmd") return m.optString("a").let { a -> mainExecutor.execute { media?.command(a) } }
        if (m.optString("t") != "open") return
        val id = m.optString("id")
        val sbn = tracked.remove(id) ?: return
        link?.openNotification(sbn.notification.contentIntent, sbn.packageName, m.optInt("display", -1))
    }

    private fun sendIcons() {
        thread(name = "icons") {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pkgs = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
            pkgs.chunked(ICONS_PER_CALL).forEachIndexed { i, chunk ->
                val list = JSONArray()
                for (pkg in chunk) appIcon(pkg, ICON_PX)?.let { list.put(JSONObject().put("pkg", pkg).put("icon", it)) }
                // First batch replaces the engine's set, the rest add to it.
                link?.send(JSONObject().put("t", "icons").put("first", i == 0).put("list", list))
            }
        }
    }

    private val iconCache = HashMap<Pair<String, Int>, String?>()

    private fun appIcon(pkg: String, px: Int): String? = synchronized(iconCache) {
        iconCache.getOrPut(pkg to px) {
            try { pngDataUrl(packageManager.getApplicationIcon(pkg), px) } catch (e: Exception) { null }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Short, stable id that doesn't reveal the notification key (it contains tags). */
    private fun idFor(key: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return "n" + d.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * How the car text setting ([Prefs.CarText]) feeds [NotifInfo.decide]:
 * Always shows private content regardless of the lock screen; like the lock
 * screen (default) lets its settings decide; Never also drops the message text.
 */
data class CarTextRule(val allowPrivate: Boolean, val hideContent: Boolean, val stripText: Boolean) {
    companion object {
        /** The lock-screen settings are only read when [mode] needs them. */
        fun of(mode: Prefs.CarText, sensitiveOnLockscreen: () -> Boolean, notificationsOnLockscreen: () -> Boolean) = CarTextRule(
            allowPrivate = mode == Prefs.CarText.ALWAYS || sensitiveOnLockscreen(),
            hideContent = mode != Prefs.CarText.ALWAYS && !notificationsOnLockscreen(),
            stripText = mode == Prefs.CarText.NEVER,
        )
    }
}

/** What the car shows for a notification; pure, so it's unit-testable. */
data class NotifInfo(val title: String, val text: String, val kind: String) {
    /** "Never" in the car: who it's from stays, the message doesn't. */
    fun withoutText() = copy(text = "")

    companion object {
        /** Importance below this is silent/minimized: not worth the driver's eyes. */
        private const val MIN_IMPORTANCE = 3 // NotificationManager.IMPORTANCE_DEFAULT

        fun of(
            sbn: StatusBarNotification,
            ownPackage: String,
            importance: Int?,
            lockscreenOverride: Int?,
            allowPrivate: Boolean,
            hideContent: Boolean,
        ): NotifInfo? {
            val n = sbn.notification
            val pub = n.publicVersion?.extras
            return decide(
                pkg = sbn.packageName,
                ownPackage = ownPackage,
                ongoing = sbn.isOngoing,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                media = n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
                category = n.category,
                importance = importance,
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
                visibility = n.visibility,
                lockscreenOverride = lockscreenOverride,
                allowPrivate = allowPrivate,
                hideContent = hideContent,
                publicTitle = pub?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                publicText = pub?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
        }

        fun decide(
            pkg: String,
            ownPackage: String,
            ongoing: Boolean,
            groupSummary: Boolean,
            media: Boolean,
            category: String?,
            importance: Int?,
            title: String?,
            text: String?,
            visibility: Int = Notification.VISIBILITY_PUBLIC,
            lockscreenOverride: Int? = null,
            allowPrivate: Boolean = true,
            hideContent: Boolean = false,
            publicTitle: String? = null,
            publicText: String? = null,
        ): NotifInfo? {
            val call = category == Notification.CATEGORY_CALL
            if (pkg == ownPackage || groupSummary || media) return null
            // Ongoing ones are status (navigation, downloads, music); calls are the exception.
            if (ongoing && !call) return null
            if (importance != null && importance < MIN_IMPORTANCE && !call) return null
            // The car screen is seen by passengers: same rules as the lock screen.
            val vis = minOf(visibility, lockscreenOverride?.takeIf { it != VISIBILITY_NO_OVERRIDE } ?: visibility)
            if (vis == Notification.VISIBILITY_SECRET) return null
            // As on the lock screen: private content shows unless the user turned
            // "sensitive notifications" off (or hid notifications there altogether).
            val private = (vis == Notification.VISIBILITY_PRIVATE && !allowPrivate) || hideContent
            val t = (if (private) publicTitle ?: "" else title).orEmpty().trim().takeChars(120)
            val x = (if (private) publicText ?: "" else text).orEmpty().trim().takeChars(300)
            if (private && t.isEmpty() && x.isEmpty()) return NotifInfo("", "New notification", kindOf(category))
            if (t.isEmpty() && x.isEmpty()) return null
            return NotifInfo(t, x, kindOf(category))
        }

        /** NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE (hidden constant value). */
        private const val VISIBILITY_NO_OVERRIDE = -1000

        private fun kindOf(category: String?) = when (category) {
            Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL -> "msg"
            Notification.CATEGORY_CALL -> "call"
            Notification.CATEGORY_NAVIGATION -> "nav"
            else -> "other"
        }
    }
}

/** At most [n] chars, never splitting a surrogate pair (emoji). */
fun String.takeChars(n: Int): String {
    if (length <= n) return this
    val end = if (Character.isHighSurrogate(this[n - 1])) n - 1 else n
    return substring(0, end)
}
