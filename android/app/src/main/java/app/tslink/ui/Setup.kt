package app.tslink.ui

/**
 * The setup wizard's model (pure, so it's unit-tested): what the phone
 * reported ([SetupFacts]) becomes the list of steps with their state and
 * the summary line. [SetupActivity] gathers the facts and draws the steps.
 */
enum class StepState { OK, ACTION, UNKNOWN }

enum class StepId {
    SHIZUKU_INSTALLED, SHIZUKU_RUNNING, SHIZUKU_ALLOWED,
    NOTIFICATION_PERMISSION, CAR_NOTIFICATIONS,
    BATTERY, HOTSPOT, CAR,
    XIAOMI_USB_SECURITY, XIAOMI_AUTOSTART, SAMSUNG_SLEEP, OPPO_BACKGROUND,
}

/** Setup's three pages, in order. */
enum class SetupPage(val title: String) { SHIZUKU("Shizuku"), PHONE("Battery & hotspot"), OPTIONAL("Optional") }

/** Phone makers whose own settings get in TSLink's way. */
enum class Brand {
    XIAOMI, SAMSUNG, OPPO, OTHER;

    companion object {
        /** From Build.MANUFACTURER / Build.BRAND (Redmi and POCO report Xiaomi or their own brand). */
        fun of(manufacturer: String, brand: String): Brand {
            val names = listOf(manufacturer, brand).map { it.lowercase() }
            fun any(vararg n: String) = names.any { it in n }
            return when {
                any("xiaomi", "redmi", "poco") -> XIAOMI
                any("samsung") -> SAMSUNG
                any("oneplus", "oppo", "realme") -> OPPO
                else -> OTHER
            }
        }
    }
}

/**
 * What the phone says right now. [shizukuAllowed] is null when it can't be
 * asked (Shizuku not running). [ticked] holds the steps the user marked done
 * (settings TSLink can't read).
 */
data class SetupFacts(
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuAllowed: Boolean?,
    val notificationPermission: Boolean,
    val carNotifications: Boolean,
    val batteryUnrestricted: Boolean,
    val carChosen: Boolean,
    val brand: Brand,
    val ticked: Set<StepId>,
)

/**
 * One step. [action] is the button label, null when there's nothing left
 * to do. [tickable]: TSLink can't check it, the user marks it done.
 * [actionEnabled] is false while an earlier step blocks this one.
 */
data class SetupStep(
    val id: StepId,
    val title: String,
    val text: String,
    val action: String?,
    val state: StepState,
    val optional: Boolean = false,
    val tickable: Boolean = false,
    val actionEnabled: Boolean = true,
)

object Setup {
    private fun ok(b: Boolean) = if (b) StepState.OK else StepState.ACTION

    private fun tick(f: SetupFacts, id: StepId) = if (id in f.ticked) StepState.OK else StepState.UNKNOWN

    private fun todo(done: Boolean, action: String) = if (done) null else action

    fun steps(f: SetupFacts): List<SetupStep> = buildList {
        add(SetupStep(
            StepId.SHIZUKU_INSTALLED, "Shizuku installed",
            if (f.shizukuInstalled) "Shizuku gives TSLink the rights to show apps in the car. No root needed."
            else "TSLink needs the free Shizuku app to show apps in the car. No root needed.",
            todo(f.shizukuInstalled, "Get Shizuku"), ok(f.shizukuInstalled),
        ))
        add(SetupStep(
            StepId.SHIZUKU_RUNNING, "Shizuku running",
            when {
                f.shizukuRunning -> "Shizuku is running. After a phone restart, start it again."
                !f.shizukuInstalled -> "Install Shizuku first."
                else -> "Open Shizuku and start it: with Wireless debugging (on Wi-Fi, Shizuku shows the steps) " +
                    "or over USB from a computer. Start it again after each phone restart."
            },
            todo(f.shizukuRunning, "Open Shizuku"), ok(f.shizukuRunning), actionEnabled = f.shizukuInstalled,
        ))
        add(SetupStep(
            StepId.SHIZUKU_ALLOWED, "TSLink allowed in Shizuku",
            when (f.shizukuAllowed) {
                true -> "Shizuku lets TSLink start its engine."
                false -> "Allow TSLink when Shizuku asks."
                null -> "Start Shizuku first, then allow TSLink."
            },
            todo(f.shizukuAllowed == true, "Allow TSLink"),
            when (f.shizukuAllowed) { true -> StepState.OK; false -> StepState.ACTION; null -> StepState.UNKNOWN },
            actionEnabled = f.shizukuAllowed == false,
        ))
        add(SetupStep(
            StepId.BATTERY, "Battery: unrestricted",
            if (f.batteryUnrestricted) "Android won't stop TSLink in the background."
            else "So Android doesn't stop TSLink while you drive: App info -> Battery -> Unrestricted.",
            todo(f.batteryUnrestricted, "Open App info"), ok(f.batteryUnrestricted),
        ))
        add(SetupStep(
            StepId.HOTSPOT, "Hotspot settings",
            "In the hotspot settings: security WPA2-Personal (the car may not see WPA3), " +
                "and turn off \"Turn off hotspot automatically\". Then mark this done.",
            "Open hotspot settings", tick(f, StepId.HOTSPOT), tickable = true,
        ))
        when (f.brand) {
            Brand.XIAOMI -> {
                add(SetupStep(
                    StepId.XIAOMI_USB_SECURITY, "Xiaomi: USB debugging (Security settings)",
                    "Without it, touches from the car don't work. Developer options -> " +
                        "turn on \"USB debugging (Security settings)\" (needs a Mi account and a SIM).",
                    "Open Developer options", tick(f, StepId.XIAOMI_USB_SECURITY), tickable = true,
                ))
                add(SetupStep(
                    StepId.XIAOMI_AUTOSTART, "Xiaomi: Autostart",
                    "Allow Autostart for TSLink, so it can start when the car connects.",
                    "Open Autostart", tick(f, StepId.XIAOMI_AUTOSTART), tickable = true,
                ))
            }
            Brand.SAMSUNG -> add(SetupStep(
                StepId.SAMSUNG_SLEEP, "Samsung: keep apps awake",
                "Settings -> Battery -> Background usage limits: turn off \"Put unused apps to sleep\", " +
                    "or add TSLink and Shizuku to \"Never sleeping apps\".",
                "Open battery settings", tick(f, StepId.SAMSUNG_SLEEP), tickable = true,
            ))
            Brand.OPPO -> add(SetupStep(
                StepId.OPPO_BACKGROUND, "Battery: background activity",
                "App info -> Battery: turn on \"Allow background activity\" for TSLink and for Shizuku.",
                "Open App info", tick(f, StepId.OPPO_BACKGROUND), tickable = true,
            ))
            Brand.OTHER -> Unit
        }
        add(SetupStep(
            StepId.NOTIFICATION_PERMISSION, "Status notification",
            "Shows \"Connected to car\" with a Disconnect button.",
            todo(f.notificationPermission, "Allow notifications"), ok(f.notificationPermission), optional = true,
        ))
        add(SetupStep(
            StepId.CAR_NOTIFICATIONS, "Notifications in the car",
            if (f.carNotifications) "New notifications appear as banners in the car."
            else "Turn on notification access for TSLink to see new notifications as banners in the car. " +
                "Greyed out? App info -> menu -> Allow restricted settings, then try again.",
            "Open notification access", ok(f.carNotifications), optional = true,
        ))
        add(SetupStep(
            StepId.CAR, "Start with the car",
            if (f.carChosen) "The engine and car hotspot start when the phone connects to the car's Bluetooth."
            else "Choose your car among the paired Bluetooth devices so TSLink starts when you get in.",
            if (f.carChosen) "Change car" else "Choose car", ok(f.carChosen), optional = true,
        ))
    }

    /** "3 of 6 ready" over the required steps, plus the optional ones. */
    fun summary(steps: List<SetupStep>): String {
        val (optional, required) = steps.partition { it.optional }
        fun ready(l: List<SetupStep>) = l.count { it.state == StepState.OK }
        val head = if (ready(required) == required.size) "All set: ${required.size} of ${required.size} ready"
        else "${ready(required)} of ${required.size} ready"
        return if (optional.isEmpty()) head else "$head · optional ${ready(optional)} of ${optional.size}"
    }

    fun page(id: StepId): SetupPage = when (id) {
        StepId.SHIZUKU_INSTALLED, StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_ALLOWED -> SetupPage.SHIZUKU
        StepId.NOTIFICATION_PERMISSION, StepId.CAR_NOTIFICATIONS, StepId.CAR -> SetupPage.OPTIONAL
        StepId.BATTERY, StepId.HOTSPOT,
        StepId.XIAOMI_USB_SECURITY, StepId.XIAOMI_AUTOSTART, StepId.SAMSUNG_SLEEP, StepId.OPPO_BACKGROUND -> SetupPage.PHONE
    }

    /** The Shizuku page shows one card: the first of install, start, allow that isn't done (else the last). */
    fun shizukuStep(steps: List<SetupStep>): SetupStep {
        val shizuku = steps.filter { page(it.id) == SetupPage.SHIZUKU }
        return shizuku.firstOrNull { it.state != StepState.OK } ?: shizuku.last()
    }

    fun pageReady(page: SetupPage, steps: List<SetupStep>) =
        steps.filter { page(it.id) == page && !it.optional }.all { it.state == StepState.OK }

    fun allRequiredReady(steps: List<SetupStep>) = steps.filterNot { it.optional }.all { it.state == StepState.OK }

    /** The state line under a step's title. */
    fun stateLabel(step: SetupStep): String = when (step.state) {
        StepState.OK -> if (step.tickable) "Done" else "Ready"
        StepState.ACTION -> if (step.optional) "Not set up (optional)" else "Needs action"
        StepState.UNKNOWN -> if (step.tickable) "Check it, then mark it done" else "Can't check yet"
    }
}
