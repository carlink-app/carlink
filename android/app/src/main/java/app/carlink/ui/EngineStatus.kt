package app.carlink.ui

/**
 * What an engine reply or app message means for the status card (pure, so
 * it's unit-tested). Engine states: "stopped", "waiting for car · cert ab12cd",
 * "car connected (...) · cert ...", "running on ...", "already running".
 */
sealed interface EngineStatus {
    data object Stopped : EngineStatus

    /** [state] capitalized for display; [connected] = a car session is up. */
    data class Running(val state: String, val cert: String?, val connected: Boolean) : EngineStatus

    /** In progress ("starting engine..."): shown on the state line. */
    data class Busy(val text: String) : EngineStatus

    /** Anything else: shown as a message below the state line. */
    data class Message(val text: String) : EngineStatus

    companion object {
        private const val CERT = " · cert "

        fun parse(s: String): EngineStatus = when {
            s.startsWith("stopped") || s == "engine stopped" -> Stopped
            s.startsWith("waiting for car") || s.startsWith("car connected") || s.startsWith("running on") || s == "already running" -> {
                val state = s.substringBefore(CERT)
                val cert = s.substringAfter(CERT, "").ifEmpty { null }
                Running(state.replaceFirstChar { it.uppercase() }, cert, state.startsWith("car connected"))
            }
            s.endsWith("...") -> Busy(s.replaceFirstChar { it.uppercase() })
            else -> Message(s)
        }

        /** An engine reply that reports a failure. */
        fun isError(s: String) = s.startsWith("error") || s == "denied"
    }
}
