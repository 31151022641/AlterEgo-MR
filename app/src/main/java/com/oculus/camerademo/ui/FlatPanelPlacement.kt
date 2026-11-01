package com.oculus.camerademo.ui

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

data class FlatPanelLayout(val menu: Pose, val dock: Pose, val mirror: Pose)

/** One sightline-relative layout. Remove head roll so the window stays upright. */
object FlatPanelPlacement {
    const val WINDOW_WIDTH = .94f
    const val WINDOW_HEIGHT = .68f
    const val WINDOW_DISTANCE = 1.10f
    const val DOCK_WIDTH = 1.05f
    const val DOCK_HEIGHT = .16f

    fun fromViewer(viewer: Pose): FlatPanelLayout? {
        val p = viewer.t; val q = viewer.q
        if (!listOf(p.x, p.y, p.z, q.w, q.x, q.y, q.z).all { it.isFinite() }) return null
        val norm = sqrt(q.w*q.w + q.x*q.x + q.y*q.y + q.z*q.z)
        if (norm < .5f || norm > 1.5f) return null
        val rotation = Quaternion(q.w/norm, q.x/norm, q.y/norm, q.z/norm)
        val facing = Pose(Vector3(0f), rotation)
        val forward = facing * Vector3(0f, 0f, 1f)
        var heading = forward
        // At a vertical look direction, retain heading from the horizontal
        // right axis instead of letting pitch/roll flip the panel behind us.
        if (forward.x*forward.x + forward.z*forward.z < .0025f) {
            val right = facing * Vector3(1f, 0f, 0f)
            heading = Vector3(-right.z, 0f, right.x)
        }
        val yaw = Math.toDegrees(atan2(heading.x.toDouble(), heading.z.toDouble())).toFloat()
        val horizontal = sqrt(forward.x*forward.x + forward.z*forward.z)
        // Follow a seated/reclined wearer's current sightline, instead of placing
        // the window above their view whenever they look down to press Reset.
        // At a vertical gaze, stay within 10 degrees and avoid a yaw singularity.
        val pitch = Math.toDegrees(atan2(-forward.y.toDouble(), horizontal.toDouble())).toFloat()
            .coerceIn(-80f, 80f)
        val origin = Pose(p, Quaternion(pitch, yaw, 0f))
        // Spatial panels display on their local -Z face. Match the viewer's
        // heading (Meta's UiPanelUpdateSystem), rather than turning them 180°
        // away. The lower dock tilts its visible face upward toward the wearer.
        val window = origin * Pose(Vector3(0f, 0f, WINDOW_DISTANCE))
        return FlatPanelLayout(
            window,
            // Keep the dock's top edge below the window's bottom edge in view.
            origin * Pose(Vector3(0f, -.52f, 1.10f), Quaternion(18f, 0f, 0f)),
            // The current live mirror uses the main window. Keep the legacy
            // slot centered too, without making that inactive panel visible.
            window,
        )
    }
}

/** Coalesces callbacks and waits for fresh scene frames after an OS reset. */
class FlatPanelReset {
    var pending = false; private set
    private var notBefore = 0L
    private var previous: Pose? = null
    private var firstFreshAt: Long? = null

    @Synchronized fun request(now: Long, waitForReferenceChange: Boolean = true) {
        pending = true
        notBefore = now + if(waitForReferenceChange)120L else 0L
        previous = null; firstFreshAt = null
    }

    @Synchronized fun sample(now: Long, canPlace: Boolean, viewer: Pose?): FlatPanelLayout? {
        if (!pending) return null
        if (!canPlace || viewer == null || now < notBefore) { previous = null; firstFreshAt = null; return null }
        val layout = FlatPanelPlacement.fromViewer(viewer) ?: run { previous = null; firstFreshAt = null; return null }
        val last = previous
        previous = viewer
        if(firstFreshAt==null)firstFreshAt=now
        // A reference-space transition can change the first post-reset pose.
        // Use the second consistent fresh frame, never a pre-reset cached pose.
        if (last == null) return null
        val movement = last.t - viewer.t
        val moving = movement.x*movement.x + movement.y*movement.y + movement.z*movement.z > .01f ||
            !last.q.isWithinAngleDegrees(viewer.q, 10f)
        // Prefer settled tracking, but a user looking around for lost controls
        // must not wait forever. Always use the CURRENT fresh pose at timeout.
        if(moving && now-firstFreshAt!! < 600L)return null
        pending = false; previous = null; firstFreshAt = null
        return layout
    }

    @Synchronized fun clear() { pending = false; previous = null; firstFreshAt = null }

    /** Keep the user's request, but never pair a post-resume pose with an old frame. */
    @Synchronized fun invalidateSamples() { previous = null; firstFreshAt = null }
}
