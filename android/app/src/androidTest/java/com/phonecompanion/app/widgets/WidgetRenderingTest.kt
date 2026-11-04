package com.phonecompanion.app.widgets

import android.view.View
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.runComposition
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the real provideGlance -> composition -> RemoteViews -> Android view path. */
@OptIn(ExperimentalGlanceApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    @Test
    fun runningCompositionObservesSelectionPayloadAndUnpair() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = DatabaseHolder.get(context)
        val storage = SecureStorage.getInstance(context)
        val id = requireNotNull(GlanceAppWidgetManager(context).getGlanceIdBy(
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1_000_002),
        ))
        val key = glanceKey(id)
        val renders = Channel<List<String>>(Channel.UNLIMITED)
        storage.saveEnrollment("https://example.invalid/", "dev-1", "test-token", TEST_KEY)
        val session = launch {
            CompanionWidget().runComposition(context, id, sizes = listOf(DpSize(200.dp, 200.dp)))
                .collect { views ->
                    var texts = emptyList<String>()
                    instrumentation.runOnMainSync {
                        texts = textsIn(views.apply(context, FrameLayout(context)))
                    }
                    renders.send(texts)
                }
        }
        suspend fun awaitText(expected: String) = withTimeout(10_000) {
            do {
                val texts = renders.receive()
            } while (expected !in texts)
        }
        try {
            awaitText("Not associated")
            val row = WidgetCacheEntity(
                "live-test", "text", "Live render", "now", """{"body":"First content"}""",
            )
            db.widgetCacheDao().upsert(row)
            db.widgetBindingDao().upsert(WidgetBindingEntity(CompanionWidget.PROVIDER, key, "text", row.id))
            awaitText("First content")
            db.widgetCacheDao().upsert(row.copy(payloadJson = """{"body":"Updated content"}"""))
            awaitText("Updated content")
            val secret = secretRow()
            db.widgetCacheDao().upsert(secret)
            db.widgetBindingDao().upsert(WidgetBindingEntity(CompanionWidget.PROVIDER, key, "secret", secret.id))
            awaitText("hello secret")
            // Inject an unreadable credential without adding a production test hook.
            // Use the same encrypted prefs instance so real listeners are notified.
            val prefsField = SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val prefs = prefsField.get(storage) as SharedPreferences
            val unreadable = object : SharedPreferences by prefs {
                override fun getString(key: String?, defValue: String?): String? {
                    if (key == "device_key") throw SecurityException("Credential read fixture")
                    return prefs.getString(key, defValue)
                }
            }
            try {
                prefsField.set(storage, unreadable)
                db.widgetCacheDao().upsert(secret.copy(updatedAt = "unreadable"))
                awaitText("Not associated")
            } finally {
                prefsField.set(storage, prefs)
            }
            storage.saveEnrollment("https://example.invalid/", "dev-1", "repaired-token", TEST_KEY)
            awaitText("hello secret")
            // Rotating credentials must invalidate plaintext already on screen.
            storage.saveEnrollment("https://example.invalid/", "dev-1", "test-token", "wrong-key")
            awaitText(WidgetFallbackCopy.SECRET_MASKED)
            db.widgetBindingDao().upsert(WidgetBindingEntity(CompanionWidget.PROVIDER, key, "none", null))
            awaitText("Not associated")
            storage.clearCredentials()
            awaitText("Not paired yet")
        } finally {
            session.cancelAndJoin()
            db.widgetBindingDao().delete(CompanionWidget.PROVIDER, key)
            db.widgetCacheDao().clearAll()
            storage.clearCredentials()
        }
    }

    @Test
    fun allWidgetTypesInflateRealContentAtSmallAndLargeSizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = DatabaseHolder.get(context)
        val storage = SecureStorage.getInstance(context)
        val id = requireNotNull(GlanceAppWidgetManager(context).getGlanceIdBy(
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1_000_001),
        ))
        val key = glanceKey(id)
        val row = WidgetCacheEntity(
            "render-test", "text", "Runtime render", "2026-01-01T00:00:00Z",
            """{"body":"Real widget content"}""",
        )
        try {
            storage.saveEnrollment("https://example.invalid/", "dev-1", "test-token", TEST_KEY)
            suspend fun renderedTexts(size: DpSize = DpSize(200.dp, 200.dp)): List<String> {
                val views = withTimeout(15_000) {
                    CompanionWidget().compose(context, id = id, size = size)
                }
                var texts = emptyList<String>()
                instrumentation.runOnMainSync {
                    texts = textsIn(views.apply(context, FrameLayout(context)))
                }
                return texts
            }
            assertTrue(renderedTexts().any { it.contains("Not associated", ignoreCase = true) })
            val cases = listOf(
                row to "Real widget content",
                row.copy(id = "list-test", type = "list", payloadJson = """{"items":[{"text":"A real item","done":true}]}""") to "A real item",
                row.copy(id = "status-test", type = "status", payloadJson = """{"state":"ok","detail":"All running"}""") to "All running",
                secretRow() to "hello secret",
            )
            for ((selected, expected) in cases) {
                db.widgetCacheDao().upsert(selected)
                db.widgetBindingDao().upsert(WidgetBindingEntity(CompanionWidget.PROVIDER, key, selected.type, selected.id))
                for (size in listOf(DpSize(40.dp, 40.dp), DpSize(320.dp, 200.dp))) {
                    val texts = renderedTexts(size)
                    assertTrue("${selected.type}: actual RemoteViews must contain the payload: $texts", texts.contains(expected))
                }
            }
        } finally {
            db.widgetBindingDao().delete(CompanionWidget.PROVIDER, key)
            db.widgetCacheDao().clearAll()
            storage.clearCredentials()
        }
    }

    private fun textsIn(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { textsIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    // Public cross-language vector from SecretCryptoTest/backend crypto tests.
    private fun secretRow() = WidgetCacheEntity(
        "hello", "secret", "Secret render", "now",
        """{"alg":"AES-256-GCM","kid":"dev-1","nonce":"oKGio6Slpqeoqaqr","ciphertext":"jn0QQSrrcdoBF+KnfT0X7AnZ7CubwyCVijmu9A=="}""",
        "dev-1",
    )

    companion object {
        private const val TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    }
}
