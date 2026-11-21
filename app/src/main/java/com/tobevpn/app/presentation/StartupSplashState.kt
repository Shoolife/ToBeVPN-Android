package com.tobevpn.app.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the startup splash animation is over, for windows that must not cover it. */
object StartupSplashState {
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun finished() {
        _done.value = true
    }
}
