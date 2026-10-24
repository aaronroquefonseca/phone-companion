package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetPayloadParser

/**
 * Total load models for the four Glance widgets (broken-widget hardening).
 *
 * Every `provideGlance` path funnels through these pure functions before
 * touching Glance composition. Each model carries render-ready strings with
 * a non-blank guarantee ([nonBlank]): blank/empty server values (blank
 * title, empty body, blank list label, blank status detail) and every
 * failure state (unbound instance, stale binding, empty cache,
 * unparseable payload, decrypt failure) map to explicit fallback copy —
 * never null, never blank, never another row's content.
 *
 * Unbound contract (replaces the old miss→first-row default): a null
 * binding, a stale binding (pinned id gone from the cache), or an empty
 * cache resolves to no row ([WidgetBindingResolver.resolve] returns null)
 * and the model renders the explicit "Not associated" state with
 * [unbound] true, so the widget can route its tap to the configuration
 * activity (see [tapFor]). Stale bindings still surface a
 * `was-<old id>` re-point hint via [WidgetBindingResolver.staleHint] —
 * shown by the config/assignment UI, never as widget content.
 */
object WidgetContent {

    /** Where a widget tap goes: the app router, or the configuration. */
    enum class TapDestination {
        ROUTER,
        CONFIG,
    }

    /**
     * Tap routing: an unbound instance on a paired phone opens the
     * configuration so the user can pick a row; everything else (bound
     * instances, unpaired phones) goes through the router. Pure and
     * JVM-testable.
     */
    fun tapFor(paired: Boolean, unbound: Boolean): TapDestination =
        if (paired && unbound) TapDestination.CONFIG else TapDestination.ROUTER

    /** Explicit unassociated title: human words, no internal ids. */
    const val UNASSOCIATED_TITLE = "Not associated"

    /** Explicit unassociated body: tells the user to tap. */
    const val UNASSOCIATED_BODY = "Tap to choose what this shows."

    /**
     * Non-blank guarantee: blank/null inputs (empty bodies, blank titles,
     * blank labels) render the fallback copy instead of reaching Glance
     * composition as an empty string. Never throws for any input.
     */
    fun nonBlank(value: String?, fallback: String): String =
        if (value.isNullOrBlank()) fallback else value

    /** Unbound title: pairing guidance when never paired, else "Not associated". */
    fun unboundTitle(widgetLabel: String, reason: WidgetFallbackCopy.Reason): String =
        when (reason) {
            WidgetFallbackCopy.Reason.NEVER_PAIRED ->
                WidgetFallbackCopy.titleFor(widgetLabel, reason)
            WidgetFallbackCopy.Reason.LOAD_FAILED -> UNASSOCIATED_TITLE
        }

    /** Unbound body: pairing guidance when never paired, else the tap hint. */
    fun unboundBody(reason: WidgetFallbackCopy.Reason): String =
        when (reason) {
            WidgetFallbackCopy.Reason.NEVER_PAIRED -> WidgetFallbackCopy.bodyFor(reason)
            WidgetFallbackCopy.Reason.LOAD_FAILED -> UNASSOCIATED_BODY
        }

    data class TextModel(
        val title: String,
        val body: String,
        val unbound: Boolean,
        val paired: Boolean,
    )

    fun textModel(
        rows: List<WidgetCacheEntity>,
        widgetId: String?,
        paired: Boolean,
    ): TextModel {
        val reason = WidgetFallbackCopy.reasonFor(paired)
        val fallbackTitle = WidgetFallbackCopy.titleFor("Phone Companion", reason)
        val fallbackBody = WidgetFallbackCopy.bodyFor(reason)
        val row = WidgetBindingResolver.resolve(rows, widgetId)
            ?: return TextModel(unboundTitle("Phone Companion", reason), unboundBody(reason), true, paired)
        val parsed = WidgetPayloadParser.parseTextBody(row.payloadJson)
            ?: return TextModel(fallbackTitle, fallbackBody, false, paired)
        return TextModel(
            title = nonBlank(WidgetPayloadParser.sanitizeTitle(row.title), fallbackTitle),
            body = nonBlank(parsed, fallbackBody),
            unbound = false,
            paired = paired,
        )
    }

    data class ListModel(
        val title: String,
        val visible: List<WidgetPayloadParser.ListItem>,
        val overflow: String?,
        val emptyHint: String,
        val unbound: Boolean,
        val paired: Boolean,
    )

    fun listModel(
        rows: List<WidgetCacheEntity>,
        widgetId: String?,
        paired: Boolean,
    ): ListModel {
        val reason = WidgetFallbackCopy.reasonFor(paired)
        val fallbackTitle = WidgetFallbackCopy.titleFor("List", reason)
        val fallbackBody = WidgetFallbackCopy.bodyFor(reason)
        val row = WidgetBindingResolver.resolve(rows, widgetId)
            ?: return ListModel(
                unboundTitle("List", reason), emptyList(), null, unboundBody(reason), true, paired,
            )
        val parsed = WidgetPayloadParser.parseListItems(row.payloadJson)
            ?: return ListModel(fallbackTitle, emptyList(), null, fallbackBody, false, paired)
        val (visible, overflow) = WidgetFallbackCopy.visibleWithOverflow(parsed)
        return ListModel(
            title = nonBlank(WidgetPayloadParser.sanitizeTitle(row.title), fallbackTitle),
            // Blank labels render as an explicit marker: a blank row must
            // never reach composition as an empty string.
            visible = visible.map { item ->
                item.copy(label = nonBlank(item.label, "—"))
            },
            overflow = overflow,
            emptyHint = fallbackBody,
            unbound = false,
            paired = paired,
        )
    }

    data class StatusModel(
        val state: String,
        val detail: String,
        val unbound: Boolean,
        val paired: Boolean,
    )

    fun statusModel(
        rows: List<WidgetCacheEntity>,
        widgetId: String?,
        paired: Boolean,
    ): StatusModel {
        val reason = WidgetFallbackCopy.reasonFor(paired)
        val fallbackTitle = WidgetFallbackCopy.titleFor("No status", reason)
        val fallbackBody = WidgetFallbackCopy.bodyFor(reason)
        val row = WidgetBindingResolver.resolve(rows, widgetId)
            ?: return StatusModel(unboundTitle("No status", reason), unboundBody(reason), true, paired)
        val parsed = WidgetPayloadParser.parseStatus(row.payloadJson)
            ?: return StatusModel(fallbackTitle, fallbackBody, false, paired)
        return StatusModel(
            state = nonBlank(parsed.state, fallbackTitle),
            detail = nonBlank(parsed.detail, fallbackBody),
            unbound = false,
            paired = paired,
        )
    }

    data class SecretModel(
        val title: String,
        val body: String,
        val unbound: Boolean,
        val paired: Boolean,
    )

    /**
     * Secret model: the widget resolves the row and the decrypt outcome
     * ([resolveVisibleSecret], null for EVERY failure mode), and this maps
     * them to strings. Never-paired renders pairing guidance; every other
     * empty state renders the single masked line (no oracle: the copy never
     * distinguishes failure modes).
     */
    fun secretModel(
        row: WidgetCacheEntity?,
        neverPaired: Boolean,
        visible: String?,
    ): SecretModel {
        if (neverPaired) {
            val reason = WidgetFallbackCopy.Reason.NEVER_PAIRED
            return SecretModel(
                title = WidgetFallbackCopy.titleFor("Secret", reason),
                body = WidgetFallbackCopy.bodyFor(reason),
                unbound = row == null,
                paired = false,
            )
        }
        val reason = WidgetFallbackCopy.Reason.LOAD_FAILED
        val fallbackTitle = WidgetFallbackCopy.titleFor("Secret", reason)
        return SecretModel(
            title = nonBlank(
                row?.let { WidgetPayloadParser.sanitizeTitle(it.title) },
                fallbackTitle,
            ),
            body = nonBlank(visible, WidgetFallbackCopy.SECRET_MASKED),
            unbound = row == null,
            paired = true,
        )
    }
}
