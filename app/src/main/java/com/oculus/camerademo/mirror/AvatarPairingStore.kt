package com.oculus.camerademo.mirror

import android.content.Context

/** App-private, excluded from Android backup by the app manifest. Remember identity only after authenticated pairing. */
class AvatarPairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_webcam_pairing",Context.MODE_PRIVATE)
    fun trustedPin(endpoint: AvatarEndpoint): String? = prefs.getString("pin:${endpoint.key}",null)
    fun remember(endpoint: AvatarEndpoint, pairing: AvatarPairing) {
        check(prefs.edit().putString("pin:${endpoint.key}",pairing.certificateSha256)
            .putString("endpoint",endpoint.key).putString("code",pairing.code).commit()) { "Could not save PC identity" }
    }
    fun restore() {
        MirrorState.endpoint = prefs.getString("endpoint","") ?: ""
        MirrorState.pairCode = prefs.getString("code","") ?: ""
    }
    fun forget(endpointValue: String) {
        val endpoint = AvatarEndpoint.parse(endpointValue)
        prefs.edit().remove("pin:${endpoint.key}").remove("endpoint").remove("code").apply()
        MirrorState.pairCode = ""
    }
}
