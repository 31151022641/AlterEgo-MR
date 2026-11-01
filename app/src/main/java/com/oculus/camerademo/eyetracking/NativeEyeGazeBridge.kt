package com.oculus.camerademo.eyetracking

/** The native side owns only its tracker and VIEW space, never the SDK's instance/session. */
internal object NativeEyeGazeBridge {
    init { System.loadLibrary("alterego_eye_gaze") }
    external fun create(instanceHandle: Long, sessionHandle: Long, procAddrHandle: Long): Long
    external fun sample(tracker: Long): FloatArray?
    external fun destroy(tracker: Long)
}
