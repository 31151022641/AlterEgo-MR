package com.oculus.camerademo.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Two bounded local files; no poses, camera images, account data or network traffic.
 * The scene thread aggregates primitives. Formatting/storage run on one background worker. */
class HandPerformanceLog(context: Context) {
    private val app = context.applicationContext
    private val stats = SceneFrameWindow()
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "AlterEgoPerfLog").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())
    private var file: File? = null // Worker-thread only.
    private var warned = false
    private var closed = false
    private var hands = -1
    private var activeHands = 0
    private var controllers = 0
    private var menu = false

    init {
        writer.execute {
            try {
                val directory = File(app.getExternalFilesDir(null) ?: app.filesDir, "diagnostics")
                if (!directory.isDirectory && !directory.mkdirs()) error("Cannot create diagnostics directory")
                file = File(directory, "hand-performance.log")
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                append("session timeMs=${System.currentTimeMillis()} version=${info.versionName} code=${info.longVersionCode} " +
                    "format=1 flags=mirror:1,camera:2,menu:4,focused:8 " +
                    "metrics=scene-callback-only-not-compositor-or-motion-latency")
            } catch (e: Exception) { warn(e) }
        }
    }

    @Synchronized fun input(hands: Int, activeHands: Int, controllers: Int, menu: Boolean) {
        this.hands = hands; this.activeHands = activeHands
        this.controllers = controllers; this.menu = menu
    }

    @Synchronized fun frame(startNs: Long, sdkEndNs: Long, endNs: Long, active: Boolean, flags: Int) {
        if (closed) return
        val summary = if (active) stats.record(startNs, sdkEndNs, endNs, flags,
            hands, activeHands, controllers, menu) else stats.drain()
        clearInput()
        if (summary != null) enqueue(summary, if (active) "window" else "inactive")
    }

    @Synchronized fun suspend(reason: String) {
        if (closed) return
        stats.drain()?.let { enqueue(it, reason) }
        clearInput()
    }

    @Synchronized fun close() {
        if (closed) return
        suspend("destroyed")
        closed = true
        writer.shutdown() // Drains accepted writes without blocking UI/scene threads.
    }

    private fun clearInput() { hands = -1; activeHands = 0; controllers = 0; menu = false }

    private fun enqueue(s: SceneFrameWindow.Summary, reason: String) {
        writer.execute {
            fun ms(ns: Long) = String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0)
            val intervalMean = if (s.intervals > 0) ms(s.intervalTotalNs / s.intervals) else "NA"
            append("sample timeMs=${System.currentTimeMillis()} reason=$reason flags=${s.flags} frames=${s.frames} " +
                "durationMs=${ms(s.durationNs)} intervals=${s.intervals} intervalMeanMs=$intervalMean " +
                "intervalMaxMs=${ms(s.intervalMaxNs)} gapsOver25ms=${s.gapsOver25ms} " +
                "sdkPhaseMeanMs=${ms(s.sdkTotalNs / s.frames)} sdkPhaseMaxMs=${ms(s.sdkMaxNs)} " +
                "appPhaseMeanMs=${ms(s.appTotalNs / s.frames)} appPhaseMaxMs=${ms(s.appMaxNs)} " +
                "inputFrames=${s.inputFrames} handSourceFrames=${s.handFrames} activeHandInputFrames=${s.activeHandFrames} " +
                "handsMax=${s.maxHands} controllersMax=${s.maxControllers} menuActions=${s.menuActions}")
        }
    }

    private fun append(line: String) {
        try {
            val target = file ?: return
            if (target.length() >= 64 * 1024) {
                val previous = File(target.parentFile, "hand-performance.previous.log")
                if (previous.exists() && !previous.delete()) error("Cannot rotate previous diagnostics")
                if (!target.renameTo(previous)) error("Cannot rotate diagnostics")
            }
            target.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Exception) { warn(e) }
    }

    private fun warn(e: Exception) {
        if (!warned) { warned = true; Log.w("AlterEgoHandPerf", "Local performance log unavailable", e) }
    }
}
