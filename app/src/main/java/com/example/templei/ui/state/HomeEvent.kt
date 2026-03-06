package com.example.templei.ui.state

sealed interface HomeEvent {
    data object PulsePressed : HomeEvent
}
