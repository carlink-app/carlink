package app.tslink.ui

/** The main screen's one big button. */
enum class Primary { FINISH_SETUP, PAIR, DISCONNECT }

enum class HomeColor { OK, WARN, MUTED }

/** What the status card shows: [state] line, optional [hint] below it, the [primary] button. */
data class HomeView(val state: String, val hint: String?, val primary: Primary, val color: HomeColor)

/**
 * The main screen's model (pure, so it's unit-tested): setup readiness and
 * the last engine status become one next action. No "engine" in the words:
 * to the user TSLink is just on or off.
 */
object Home {
    const val CAR_HINT = "In the car: join this phone's hotspot and open tslink.app"

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
}
