package com.oculus.camerademo.renderer

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.oculus.camerademo.camera.CameraExtrinsics
import com.oculus.camerademo.ml.TrackedHeadObject

object SceneCoordinateMapping {
    /** Full capture-time pose; camera graphics -Z forward becomes Spatial SDK +Z forward. */
    fun cameraHeadToWorld(capturedViewer: Pose, extrinsics: CameraExtrinsics, head: TrackedHeadObject): Pose {
        val cameraPose = Pose(Vector3(extrinsics.translationX, extrinsics.translationY, extrinsics.translationZ),
            Quaternion(extrinsics.rotationW, extrinsics.rotationX, extrinsics.rotationY, extrinsics.rotationZ))
        val cameraHeadPose = cameraPose * Pose(Vector3(head.anchorX, head.anchorY, head.anchorZ),
            Quaternion(head.orientation.w, head.orientation.x, head.orientation.y, head.orientation.z))
        val localPose = Pose(Vector3(cameraHeadPose.t.x, cameraHeadPose.t.y, -cameraHeadPose.t.z),
            Quaternion(cameraHeadPose.q.w, -cameraHeadPose.q.x, -cameraHeadPose.q.y, cameraHeadPose.q.z))
        return capturedViewer * localPose
    }
}
