package app.tslink.ui

/** The main screen's one big button. */
enum class Primary { FINISH_SETUP, PAIR, DISCONNECT }

enum class HomeColor { OK, WARN, MUTED }

/** After a status: whether a Pair tap is still waiting for TSLink to run, and whether to open the scanner now. */
data class ScanStep(val pending: Boolean, val open: Boolean)

/** What the status card shows: [state] line, optional [hint] below it, the [primary] button. */
data class HomeView(val state: String, val hint: String?, val primary: Primary, val color: HomeColor)

/**
 * The main screen's model (pure, so it's unit-tested): setup readiness and
 * the last engine status become one next action. No "engine" in the words:
 * to the user TSLink is just on or off.
 */
object Home {
    const val CAR_HINT = "Turn on the car hotspot. In the car, join it and open tslink.app, then pair."

    fun view(setupReady: Boolean, status: EngineStatus): HomeView {
        // Shizuku stopped or not allowed: nothing else works, whatever the engine last said.
        if (!setupReady) return HomeView("Finish setup to use TSLink", null, Primary.FINISH_SETUP, HomeColor.WARN)
        return when (status) {
            is EngineStatus.Running ->
                if (status.connected) HomeView("Connected to the car", null, Primary.DISCONNECT, HomeColor.OK)
                else HomeView("Ready: waiting for the car", CAR_HINT, Primary.PAIR, HomeColor.WARN)
            // "Starting engine..." / "Checking engine..." without the word.
            is EngineStatus.Busy -> HomeView(status.text.replace(" engine", ""), null, Primary.PAIR, HomeColor.MUTED)
            // A message (an error, a pairing note) is shown next to the button; the state is off.
            EngineStatus.Stopped, is EngineStatus.Message -> HomeView("Off", null, Primary.PAIR, HomeColor.MUTED)
        }
    }

    /**
     * Pair tapped while off waits for TSLink to run, then opens the scanner
     * once. Stopped or any message (a refusal: Shizuku not running, permission
     * denied, an error) drops it, so the scanner never opens unasked later.
     */
    fun scanAfter(pending: Boolean, status: EngineStatus): ScanStep = when {
        !pending -> ScanStep(pending = false, open = false)
        status is EngineStatus.Running -> ScanStep(pending = false, open = true)
        status is EngineStatus.Busy -> ScanStep(pending = true, open = false)
        else -> ScanStep(pending = false, open = false)
    }

    /**
     * The note under the button (a refusal, "Paired with the car at ...")
     * goes when TSLink comes up, not on every poll or car coming and going.
     */
    fun clearsNote(before: EngineStatus, now: EngineStatus) =
        now is EngineStatus.Running && before !is EngineStatus.Running
}
