package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSessionStateMachineTest {
    @Test
    fun transitions_follow_authoritative_lifecycle() {
        val machine = StreamSessionStateMachine()
        assertEquals(StreamSessionStateMachine.State.IDLE, machine.currentState())
        machine.transition(StreamSessionStateMachine.Event.PreviewBound)
        assertEquals(StreamSessionStateMachine.State.PREVIEW_READY, machine.currentState())
        machine.transition(StreamSessionStateMachine.Event.StartRequested)
        assertEquals(StreamSessionStateMachine.State.STREAM_INITIALIZING, machine.currentState())
        machine.transition(StreamSessionStateMachine.Event.StartSucceeded)
        assertEquals(StreamSessionStateMachine.State.STREAM_ACTIVE, machine.currentState())
        machine.transition(StreamSessionStateMachine.Event.StopRequested)
        assertEquals(StreamSessionStateMachine.State.STREAM_STOPPING, machine.currentState())
        machine.transition(StreamSessionStateMachine.Event.StopSucceeded)
        assertEquals(StreamSessionStateMachine.State.IDLE, machine.currentState())
    }
}
