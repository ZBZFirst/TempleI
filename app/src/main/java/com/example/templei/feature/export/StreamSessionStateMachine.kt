package com.example.templei.feature.export

/**
 * Authoritative stream session state machine used by export/session coordination.
 */
class StreamSessionStateMachine(initialState: State = State.IDLE) {
    enum class State {
        IDLE,
        PREVIEW_READY,
        STREAM_INITIALIZING,
        STREAM_ACTIVE,
        STREAM_FAILED,
        STREAM_STOPPING,
    }

    sealed interface Event {
        data object PreviewBound : Event
        data object StartRequested : Event
        data object StartSucceeded : Event
        data class StartFailed(val reason: String) : Event
        data object StopRequested : Event
        data object StopSucceeded : Event
        data class StopFailed(val reason: String) : Event
        data object Reset : Event
    }

    data class TransitionResult(
        val previous: State,
        val current: State,
        val accepted: Boolean,
    )

    private var state: State = initialState

    fun currentState(): State = state

    fun transition(event: Event): TransitionResult {
        val previous = state
        val next = when (state) {
            State.IDLE -> when (event) {
                Event.PreviewBound -> State.PREVIEW_READY
                Event.StartRequested -> State.STREAM_INITIALIZING
                Event.Reset -> State.IDLE
                else -> state
            }

            State.PREVIEW_READY -> when (event) {
                Event.StartRequested -> State.STREAM_INITIALIZING
                Event.Reset -> State.IDLE
                else -> state
            }

            State.STREAM_INITIALIZING -> when (event) {
                Event.StartSucceeded -> State.STREAM_ACTIVE
                is Event.StartFailed -> State.STREAM_FAILED
                Event.StopRequested -> State.STREAM_STOPPING
                else -> state
            }

            State.STREAM_ACTIVE -> when (event) {
                Event.StopRequested -> State.STREAM_STOPPING
                is Event.StartFailed -> State.STREAM_FAILED
                Event.Reset -> State.IDLE
                else -> state
            }

            State.STREAM_FAILED -> when (event) {
                Event.StartRequested -> State.STREAM_INITIALIZING
                Event.StopRequested -> State.STREAM_STOPPING
                Event.Reset -> State.IDLE
                else -> state
            }

            State.STREAM_STOPPING -> when (event) {
                Event.StopSucceeded -> State.IDLE
                is Event.StopFailed -> State.STREAM_FAILED
                Event.Reset -> State.IDLE
                else -> state
            }
        }
        state = next
        return TransitionResult(previous = previous, current = next, accepted = previous != next)
    }
}
