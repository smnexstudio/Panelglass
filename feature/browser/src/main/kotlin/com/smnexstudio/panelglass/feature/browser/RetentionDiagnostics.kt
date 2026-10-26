package com.smnexstudio.panelglass.feature.browser

/**
 * Why the saved translations did, or did not, come back.
 *
 * The reason is an enum, never a sentence: the logs carry a number and a name, and nothing that could hold a
 * URL, a line of text, a character's name or a key.
 */
enum class RetentionReason {
    /** A translation was accepted and put on the screen. */
    ACCEPTED_FRESH,

    /** A translation was accepted and its write was started. */
    SAVE_QUEUED,

    /** The write landed. */
    SAVE_COMMITTED,

    /** The translation may not be kept: the image is a canvas, a data picture, or is unknown. */
    SAVE_REJECTED,

    /** The write failed. The class of the failure is logged, never its message. */
    SAVE_FAILED,

    /** An overlay was dropped because it was too far from the viewport. */
    DECODED_EVICTED_DISTANCE,

    /** An overlay was dropped because the memory budget was reached. */
    DECODED_EVICTED_BYTES,

    /** An overlay was dropped because the phone asked for memory. */
    DECODED_EVICTED_MEMORY_TRIM,

    /** An overlay was dropped because the whole-page scan's budget was reached. */
    DECODED_EVICTED_WHOLE_PAGE,

    /** The records of an image were read. */
    RECORDS_LOADED,

    /** The records were made with other settings, so they are not shown. */
    FINGERPRINT_MISMATCH,

    /** The image may not be kept: a canvas, a data picture, or nothing. */
    UNSUPPORTED_IDENTITY,

    /** The image's key appears more than once and the record could not be tied to one of them. */
    AMBIGUOUS_OCCURRENCE,

    /** The image's shape changed: a different picture, or a different layout. */
    ASPECT_MISMATCH,

    /** The record was made by the broken version and its geometry cannot be trusted. */
    LEGACY_GEOMETRY_INVALID,

    /** The record lies outside the restore window. */
    OUTSIDE_WINDOW,

    /** The record's bytes could not be decoded. */
    DECODE_FAILED,

    /** The screen already has that bubble. */
    DUPLICATE,

    /** A record was found that matches what is on screen. */
    RESTORED_CANDIDATE,

    /** A record was put on the screen. */
    PUBLISHED_RESTORE,

    /** A restored translation was dropped again straight away. */
    RESTORED_THEN_EVICTED,

    /** A clear advanced the store's epoch. */
    CLEAR_EPOCH_ADVANCED,

    /** A write that belonged to a state that no longer exists was refused. */
    STALE_WRITE_REJECTED,

    /** A result that belonged to another generation was not put on the screen. */
    STALE_PUBLISH_REJECTED,

    /** The store's own count, and the number of bytes it really holds. */
    STORE_BYTES,

    /** How many writes were still in flight. */
    PENDING_SAVE_BYTES,
}

/**
 * The numbers a diagnostic sink records.
 *
 * Only numbers and enum names: the count of a thing, its size, a reason. No text, no URL, no key, no name.
 */
data class RetentionEvent(
    val reason: RetentionReason,
    /** A count: how many, how many bytes, how many screens. */
    val count: Long = 0,
    /** A second count, when a reason has two: the candidates and the published ones, for instance. */
    val count2: Long = 0,
    /** A number of milliseconds, when the reason is about time. */
    val elapsedMs: Long = 0,
) {
    /** The line that goes to the log: the name and the numbers, nothing else. */
    override fun toString(): String = buildString {
        append(reason.name)
        if (count != 0L) append(" count=").append(count)
        if (count2 != 0L) append(" count2=").append(count2)
        if (elapsedMs != 0L) append(" ms=").append(elapsedMs)
    }
}

/**
 * Where the diagnostics go.
 *
 * The default sink writes to the log. A test can take it over and read the events, which is how the counts in the
 * logs are checked at all.
 */
interface RetentionDiagnostics {
    /** One event happened. */
    fun record(event: RetentionEvent)
}

/**
 * The default sink: the log, under the reader's own tag.
 *
 * The log is not always there — a unit test has no Android framework — and a diagnostic that crashes the app it
 * is meant to explain is worse than no diagnostic. A failure to log is swallowed; the event is still counted by
 * whoever asked for it.
 */
object LogRetentionDiagnostics : RetentionDiagnostics {
    override fun record(event: RetentionEvent) {
        try {
            android.util.Log.d("ScreenSession", event.toString())
        } catch (_: Throwable) {
            // No framework: nothing to log to, and nothing to do about it.
        }
    }
}

/**
 * The sink the reader uses.
 *
 * It is a single place, so a test can watch it and a future release can send it somewhere else without touching
 * the code that produces the events.
 */
object RetentionDiagnosticsHub {
    @Volatile
    private var sink: RetentionDiagnostics = LogRetentionDiagnostics

    /** Where the events go now. */
    var current: RetentionDiagnostics
        get() = sink
        set(value) { sink = value }

    /** One event happened. */
    fun record(event: RetentionEvent) = sink.record(event)
}
