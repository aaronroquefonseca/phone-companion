package com.phonecompanion.app.notify

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground-only in-app surfacing for fresh install commands.
 *
 * The auto-download always proceeds on receipt (background or
 * foreground); this hub additionally offers each fresh command to the
 * visible UI exactly once. Activities register in `onStart` and
 * unregister in `onStop`, so observer presence IS the foreground gate:
 * an emit with no registered observer (background/worker context)
 * delivers nowhere and the status-bar notification covers that case.
 *
 * Delivery is one-shot per per-device command id (same posted-set
 * discipline as the notification path): the first emit marks the id and
 * later emits for it are dropped, so rotation (re-registration) and
 * re-polls can never duplicate the dialog. Within one emit, observers
 * are tried in registration order and the first that ACCEPTS (returns
 * true) wins — during an activity transition at most one screen is
 * resumed, so exactly one dialog appears. An observer returning false
 * (not resumed) or throwing never blocks the rest.
 *
 * Pure JVM (zero Android imports): registration, one-shot dedupe, and
 * first-accepts-wins are unit-testable without an emulator. Observers
 * own their thread hop (activities must post to the main thread).
 */
object AppInstallEvents {

    /** Foreground observer: return true when the command was surfaced. */
    fun interface Observer {
        fun onInstallCommand(payload: AppInstallPayload): Boolean
    }

    private val observers = CopyOnWriteArrayList<Observer>()
    private val delivered = ConcurrentHashMap<String, Boolean>()

    fun register(observer: Observer) {
        observers.addIfAbsent(observer)
    }

    fun unregister(observer: Observer) {
        observers.remove(observer)
    }

    /**
     * Offers [payload] to the foreground observers. Returns true when an
     * observer accepted it. A repeat emit for the same command id is
     * dropped (returns false, observers untouched).
     */
    fun emit(payload: AppInstallPayload): Boolean {
        if (delivered.putIfAbsent(payload.commandId, true) != null) return false
        for (observer in observers) {
            val accepted = try {
                observer.onInstallCommand(payload)
            } catch (_: Exception) {
                false
            }
            if (accepted) return true
        }
        return false
    }

    /** Test-only reset. Production never clears (see class kdoc). */
    fun clearForTest() {
        observers.clear()
        delivered.clear()
    }
}
