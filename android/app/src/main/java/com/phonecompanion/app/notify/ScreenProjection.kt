package com.phonecompanion.app.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Optional fast capture path.
 *
 * `screencap` costs ~250 ms before any encoding: SurfaceFlinger readback
 * plus a 10 MB raw dump. MediaProjection mirrors the display into an
 * ImageReader we already own, so a capture is an `acquireLatestImage` and a
 * JPEG encode — ~30-60 ms.
 *
 * The trade-off is consent: the user allows screen access once per session
 * (system dialog), and the projection stays alive until stopped or the
 * process dies. Without it, LinkOps falls back to `screencap`, so the link
 * still works — just slower.
 */
object ScreenProjection {

    private var appContext: Context? = null

    private const val TAG = "ScreenProjection"

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    // Latest mirrored frame, decoded once per screen change. A static
    // screen produces no new frames, so without this cache every capture
    // after the first had no image to acquire and fell back to the slow
    // screencap path.
    // A mirrored display stops producing frames when the screen turns off,
    // and after waking it can keep serving the frame from before sleep.
    // Without this flag a capture returned a stale image (clock stuck at
    // the sleep time) instead of falling back to screencap.
    @Volatile
    private var staleSinceScreenOn = false
    @Volatile
    private var screenOnAtMs = 0L
    private var frameAtMs = 0L
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val frameLock = Any()
    private var framePixels: IntArray? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private val frameThread = HandlerThread("pc-frame").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    @Volatile
    var isReady: Boolean = false
        private set

    /** Consent granted, service has not started the projection yet. */
    @Volatile
    var pending: Boolean = false

    fun start(app: Context, resultCode: Int, data: Intent): Boolean {
        pending = false
        appContext = app.applicationContext
        stop()
        return try {
            val manager = app.getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(resultCode, data) ?: return false
            val dm = app.resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
            val r = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ source -> decodeFrame(source) }, frameHandler)
            val d = p.createVirtualDisplay(
                "pc-fast",
                width,
                height,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                frameHandler,
            )
            projection = p
            reader = r
            display = d
            isReady = true
            screenOnAtMs = android.os.SystemClock.elapsedRealtime()
            staleSinceScreenOn = false
            registerScreenReceiver(app)
            Log.i(TAG, "projection ready ${width}x$height")
            true
        } catch (e: Exception) {
            Log.w(TAG, "projection start failed [${e.javaClass.simpleName}]")
            stop()
            false
        }
    }

    /** Decodes one frame into the cache; runs on the frame thread. */
    private fun decodeFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                var idx = y * rowStride
                val base = y * w
                for (x in 0 until w) {
                    if (idx + 2 < buf.limit()) {
                        val rr = buf.get(idx).toInt() and 0xFF
                        val gg = buf.get(idx + 1).toInt() and 0xFF
                        val bb = buf.get(idx + 2).toInt() and 0xFF
                        pixels[base + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                    idx += pixelStride
                }
            }
            synchronized(frameLock) {
                framePixels = pixels
                frameWidth = w
                frameHeight = h
            }
            frameAtMs = android.os.SystemClock.elapsedRealtime()
            if (frameAtMs >= screenOnAtMs) staleSinceScreenOn = false
        } catch (_: Exception) {
            // A bad frame is skipped; the previous cache stays valid.
        } finally {
            image.close()
        }
    }

    /** Latest mirrored frame as JPEG at [targetWidth]; null when not ready. */
    fun capture(targetWidth: Int, quality: Int): ByteArray? {
        // No frame since the screen came back on: the cache is from before
        // sleep. Refuse it so LinkOps falls back to a live screencap.
        if (staleSinceScreenOn) return null
        val (pixels, w, h) = synchronized(frameLock) {
            val p = framePixels ?: return null
            Triple(p, frameWidth, frameHeight)
        }
        return try {
            val step = maxOf(1, (w + targetWidth - 1) / targetWidth)
            val outW = w / step
            val outH = h / step
            val outPixels = IntArray(outW * outH)
            for (y in 0 until outH) {
                var src = y * step * w
                val dst = y * outW
                for (x in 0 until outW) {
                    outPixels[dst + x] = pixels[src]
                    src += step
                }
            }
            val bitmap = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream(96 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "capture failed [${e.javaClass.simpleName}]")
            null
        }
    }

    private fun registerScreenReceiver(app: Context) {
        if (screenReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        staleSinceScreenOn = true
                        screenOnAtMs = android.os.SystemClock.elapsedRealtime()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        staleSinceScreenOn = true
                        screenOnAtMs = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { app.registerReceiver(receiver, filter) }
        screenReceiver = receiver
    }

    fun stop() {
        isReady = false
        screenReceiver?.let { runCatching { appContext?.unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    /** True when the system still has a usable grant for this process. */
    fun hasPermission(context: Context): Boolean = isReady
}
