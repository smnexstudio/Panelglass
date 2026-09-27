package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.model.EngineFailure

/**
 * Which failures are worth another try on the *same* engine, and how long to wait. Only transient provider states
 * qualify; everything else surfaces typed at once. Delays are capped so a retry never eats the screen budget
 * (a viewport should be translated within ~15 s).
 */
object EngineRetry {
    const val MAX_RATE_LIMIT_RETRIES = 3
    const val MAX_OVERLOAD_RETRIES = 2
    const val MAX_DELAY_MS = 4_000L

    /** Delay before retry number [attempt] (1-based) after [f], or null when [f] is not retried again. */
    fun delayFor(f: EngineFailure, attempt: Int): Long? = when (f) {
        is EngineFailure.RateLimited -> if (attempt <= MAX_RATE_LIMIT_RETRIES) minOf(f.retryAfterMs * attempt, MAX_DELAY_MS) else null
        is EngineFailure.Overloaded -> if (attempt <= MAX_OVERLOAD_RETRIES) minOf(f.retryAfterMs * attempt, MAX_DELAY_MS) else null
        else -> null
    }
}
