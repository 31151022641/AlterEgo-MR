package com.oculus.camerademo.diagnostics

/** Aggregate callback timings only, not compositor FPS or sensor-to-display latency.
 * Caller serializes access. No allocations until a window is emitted. */
class SceneFrameWindow(private val windowNs: Long = 5_000_000_000L) {
    private var flags = 0
    private var firstNs = 0L
    private var previousNs = 0L
    private var lastEndNs = 0L
    private var frames = 0
    private var intervals = 0
    private var intervalTotalNs = 0L
    private var intervalMaxNs = 0L
    private var gapsOver25ms = 0
    private var sdkTotalNs = 0L
    private var sdkMaxNs = 0L
    private var appTotalNs = 0L
    private var appMaxNs = 0L
    private var inputFrames = 0
    private var handFrames = 0
    private var activeHandFrames = 0
    private var maxHands = 0
    private var maxControllers = 0
    private var menuActions = 0

    fun record(startNs: Long, sdkEndNs: Long, endNs: Long, state: Int,
               hands: Int, activeHands: Int, controllers: Int, menu: Boolean): Summary? {
        // A clock discontinuity or an invalid measurement must not pollute the next window.
        if (startNs <= 0 || sdkEndNs < startNs || endNs < sdkEndNs ||
            (frames > 0 && startNs <= previousNs)) return drain()
        var emitted = if (frames > 0 && flags != state) drain() else null
        if (frames == 0) { firstNs = startNs; flags = state }
        if (previousNs > 0) {
            val interval = startNs - previousNs
            intervals++
            intervalTotalNs += interval
            intervalMaxNs = maxOf(intervalMaxNs, interval)
            if (interval > 25_000_000L) gapsOver25ms++
        }
        previousNs = startNs
        lastEndNs = endNs
        frames++
        val sdkNs = sdkEndNs - startNs
        val appNs = endNs - sdkEndNs
        sdkTotalNs += sdkNs; sdkMaxNs = maxOf(sdkMaxNs, sdkNs)
        appTotalNs += appNs; appMaxNs = maxOf(appMaxNs, appNs)
        if (hands >= 0) inputFrames++ // -1 means no SDK input sample this frame.
        if (hands > 0) handFrames++
        if (activeHands > 0) activeHandFrames++
        maxHands = maxOf(maxHands, hands)
        maxControllers = maxOf(maxControllers, controllers)
        if (menu) menuActions++
        if (emitted == null && endNs - firstNs >= windowNs) emitted = drain()
        return emitted
    }

    /** Also breaks the interval chain, so time in Home/asleep is never counted as lag. */
    fun drain(): Summary? {
        val result = if (frames == 0) null else Summary(flags, frames, lastEndNs - firstNs,
            intervals, intervalTotalNs, intervalMaxNs, gapsOver25ms, sdkTotalNs, sdkMaxNs,
            appTotalNs, appMaxNs, inputFrames, handFrames, activeHandFrames, maxHands,
            maxControllers, menuActions)
        firstNs = 0; previousNs = 0; lastEndNs = 0; frames = 0; intervals = 0
        intervalTotalNs = 0; intervalMaxNs = 0; gapsOver25ms = 0
        sdkTotalNs = 0; sdkMaxNs = 0; appTotalNs = 0; appMaxNs = 0
        inputFrames = 0; handFrames = 0; activeHandFrames = 0
        maxHands = 0; maxControllers = 0; menuActions = 0
        return result
    }

    data class Summary(val flags: Int, val frames: Int, val durationNs: Long,
        val intervals: Int, val intervalTotalNs: Long, val intervalMaxNs: Long,
        val gapsOver25ms: Int, val sdkTotalNs: Long, val sdkMaxNs: Long,
        val appTotalNs: Long, val appMaxNs: Long, val inputFrames: Int,
        val handFrames: Int, val activeHandFrames: Int, val maxHands: Int,
        val maxControllers: Int, val menuActions: Int)

    companion object {
        const val LIVE_MIRROR = 1
        const val CAMERA = 2
        const val MENU_VISIBLE = 4
        const val FOCUSED = 8
    }
}
