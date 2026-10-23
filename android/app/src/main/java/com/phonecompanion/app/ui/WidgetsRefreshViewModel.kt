package com.phonecompanion.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import com.phonecompanion.app.work.RefreshWorker

/**
 * Refresh-feedback state holder (finding 24): survives rotation, so a manual
 * refresh triggered before recreation still reports running/success/failed
 * afterwards instead of resetting to idle.
 *
 * The state mapping ([mapToState]) is pure and JVM-unit-tested. Wiring this
 * into WidgetsActivity (observe [refreshState], call [attach]) is an
 * explicit follow-up: that file is owned by the parallel poll-wipe agent and
 * must not be touched here.
 */
class WidgetsRefreshViewModel : ViewModel() {

    /** User-facing refresh state. */
    enum class RefreshState {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED,
    }

    private val _refreshState = MediatorLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    /**
     * Starts observing the manual-refresh unique work. Call from the owning
     * activity's `onCreate` (follow-up wiring); safe to call once per
     * ViewModel lifetime.
     */
    fun attach(source: LiveData<List<WorkInfo>>) {
        _refreshState.addSource(source) { infos ->
            _refreshState.value = mapToState(infos)
        }
    }

    companion object {
        /**
         * Pure WorkInfo → UI-state mapping. Latest (first) entry wins; empty
         * means nothing was ever requested. ENQUEUED counts as running (it
         * will run once constraints allow).
         */
        fun mapToState(infos: List<WorkInfo>?): RefreshState {
            val latest = infos?.firstOrNull() ?: return RefreshState.IDLE
            return when (latest.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> RefreshState.RUNNING
                WorkInfo.State.SUCCEEDED -> RefreshState.SUCCEEDED
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> RefreshState.FAILED
                WorkInfo.State.BLOCKED -> RefreshState.RUNNING
            }
        }

        /** Unique-work name this ViewModel observes (see [RefreshWorker]). */
        fun observedWorkName(): String = RefreshWorker.manualWorkName()
    }
}
