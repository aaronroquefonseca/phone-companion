package com.phonecompanion.app.ui

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshStateMapperTest {

    private fun info(state: WorkInfo.State) = WorkInfo(
        UUID.randomUUID(),
        state,
        emptySet(),
        Data.EMPTY,
        Data.EMPTY,
        0,
        0,
    )

    @Test
    fun `empty history is idle`() {
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.IDLE,
            WidgetsRefreshViewModel.mapToState(emptyList()),
        )
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.IDLE,
            WidgetsRefreshViewModel.mapToState(null),
        )
    }

    @Test
    fun `enqueued and running both report running`() {
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.RUNNING,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.ENQUEUED))),
        )
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.RUNNING,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.RUNNING))),
        )
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.RUNNING,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.BLOCKED))),
        )
    }

    @Test
    fun `terminal states map distinctly and survive rotation by observation`() {
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.SUCCEEDED,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.SUCCEEDED))),
        )
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.FAILED,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.FAILED))),
        )
        assertEquals(
            WidgetsRefreshViewModel.RefreshState.FAILED,
            WidgetsRefreshViewModel.mapToState(listOf(info(WorkInfo.State.CANCELLED))),
        )
    }

    @Test
    fun `observed work name matches the manual refresh`() {
        assertEquals("widget-refresh-manual", WidgetsRefreshViewModel.observedWorkName())
    }
}
