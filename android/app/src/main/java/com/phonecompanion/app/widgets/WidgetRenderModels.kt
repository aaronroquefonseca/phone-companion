package com.phonecompanion.app.widgets

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.emitAll

/** One stream per widget session, invalidated by selection, payload and credential changes. */
internal fun widgetRenderModels(context: Context, id: GlanceId): Flow<CompanionContent.Render> = flow {
    val storage = SecureStorage.getInstance(context)
    val selected = DatabaseHolder.get(context).widgetCacheDao()
        .observeSelected(CompanionWidget.PROVIDER, glanceKey(id))
    emitAll(combine(selected, storage.credentialChanges()) { row, _ ->
        try {
            if (!storage.isPaired) {
                CompanionContent.unboundFor(paired = false)
            } else {
                val visible = if (row?.type == "secret") {
                    resolveVisibleSecret(storage.deviceId, storage.deviceKey, row)
                } else null
                CompanionContent.resolveForRow(row, paired = true, visibleSecret = visible)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keep observing: a later enrollment can repair unreadable credentials.
            Log.e("CompanionWidget", "widget model read failed error=${error.javaClass.simpleName}")
            CompanionContent.unboundFallback()
        }
    })
}.catch { error ->
    if (error is CancellationException) throw error
    Log.e("CompanionWidget", "widget model load failed error=${error.javaClass.simpleName}")
    emit(CompanionContent.unboundFallback())
}.flowOn(Dispatchers.IO)
