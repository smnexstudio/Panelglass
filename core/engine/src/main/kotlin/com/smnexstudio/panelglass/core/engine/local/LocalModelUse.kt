package com.smnexstudio.panelglass.core.engine.local

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who needs the on-device model right now. Only one on-device model is loaded at a time ([LocalEngines]), so a Studio
 * run on one waits while the reader translates the screen with one: the reader is what the user is looking at.
 */
@Singleton
class LocalModelUse @Inject constructor() {
    /** The reader's screen translation is on, with an on-device model. */
    val reader = MutableStateFlow(false)
}
