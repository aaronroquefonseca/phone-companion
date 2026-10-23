package com.phonecompanion.app.widgets

/**
 * Shared fallback-copy vocabulary for the four per-type Glance widgets
 * (finding 33). One voice everywhere: same "not paired yet" line, same
 * "nothing to show" line, same masked-secret line mentioning masking.
 *
 * Reason-aware where the reason is known locally: a widget that can see
 * there are no credentials at all (never paired / unpaired) says so and
 * points at pairing; any other empty state (load failed, empty cache) says
 * the content is unavailable. The secret widget keeps the no-oracle policy:
 * every decrypt/load failure renders the single masked state — the copy
 * mentions masking but never distinguishes failure modes.
 */
object WidgetFallbackCopy {

    /** Why a widget has nothing to render (locally known only). */
    enum class Reason {
        /** No stored credentials: never paired, unpaired, or revoked. */
        NEVER_PAIRED,
        /** Anything else: load failure or empty cache. */
        LOAD_FAILED,
    }

    fun reasonFor(paired: Boolean): Reason =
        if (paired) Reason.LOAD_FAILED else Reason.NEVER_PAIRED

    fun titleFor(widgetLabel: String, reason: Reason): String = when (reason) {
        Reason.NEVER_PAIRED -> "Not paired yet"
        Reason.LOAD_FAILED -> widgetLabel
    }

    fun bodyFor(reason: Reason): String = when (reason) {
        Reason.NEVER_PAIRED -> "Pair this phone to show server content."
        Reason.LOAD_FAILED -> "Nothing to show yet."
    }

    /**
     * Overflow hint appended after the last visible list row when the cache
     * holds more items than the widget shows ("+3 more"). Pure and
     * unit-tested.
     */
    fun overflowHint(total: Int, shown: Int): String? {
        val rest = total - shown
        return if (total > shown && rest > 0) "+$rest more" else null
    }

    /**
     * Truncates display text with an ellipsis marker. Glance 1.1 Text has no
     * maxLines, so capped fields are ellipsized in code. Pure and
     * unit-tested.
     */
    fun ellipsize(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars == 1) return "…"
        return text.take(maxChars - 1) + "…"
    }

    /**
     * Splits list items into the visible prefix plus an optional overflow
     * hint. Never throws; non-positive [maxShown] shows nothing but the hint.
     */
    fun <T> visibleWithOverflow(
        items: List<T>,
        maxShown: Int = LIST_MAX_SHOWN,
    ): Pair<List<T>, String?> {
        if (maxShown <= 0) return emptyList<T>() to overflowHint(items.size, 0)
        val visible = items.take(maxShown)
        return visible to overflowHint(items.size, visible.size)
    }

    const val LIST_MAX_SHOWN = 5

    /**
     * The single masked line for secret widgets: value hidden, refresh
     * pending. Used for every failure mode (missing key, kid mismatch, bad
     * envelope, auth failure, empty cache) — no oracle.
     */
    const val SECRET_MASKED = "•••••• value hidden — needs refresh"
}
