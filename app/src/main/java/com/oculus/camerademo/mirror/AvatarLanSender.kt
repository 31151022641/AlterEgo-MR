package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Encrypted, certificate-pinned rendered avatar video. No plaintext or tracking-data fallback. */
class AvatarLanSender(context: Context, private val status: (String, Boolean) -> Unit) : AutoCloseable {
    private data class Frame(val bitmap: Bitmap, val capturedAt: Long)
    private val pairingStore = AvatarPairingStore(context)
    private val frameLock = Any()
    private var latest: Frame? = null
    private var lastOfferedAt = 0L
    private var clearPending = false
    private var imageInvalid = false
    private val executor = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var generation = 0L
    private var lastDiagnosticAt = 0L
    val budget = AvatarVideoBudget()
    val statusGeneration get() = generation

    @Synchronized fun start(endpointValue: String, code: String) {
        check(!closed) { "Webcam sender is closed" }
        val endpoint = AvatarEndpoint.parse(endpointValue)
        val pairing = AvatarPairing.parse(code)
        AvatarStreamProtocol.requireTrustedIdentity(pairingStore.trustedPin(endpoint),pairing.certificateSha256)
        stop()
        val request = ++generation
        running = true
        synchronized(frameLock) { lastOfferedAt = SystemClock.uptimeMillis(); clearPending = false; imageInvalid = false }
        status("Connecting securely to Windows…",true)
        executor.execute {
            var retry = 0
            while (active(request)) {
                val ssl = SSLContext.getInstance("TLS").apply {
                    init(null,arrayOf(AvatarPinnedTrustManager(pairing.certificateSha256)),SecureRandom())
                }
                val connection = ssl.socketFactory.createSocket() as SSLSocket
                var phase = "connect"
                try {
                    val bound = synchronized(this) {
                        if (active(request)) { socket = connection; true } else false
                    }
                    if (!bound) break
                    connection.enabledProtocols = connection.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    check(connection.enabledProtocols.isNotEmpty()) { "TLS 1.2 or newer is required" }
                    connection.connect(InetSocketAddress(endpoint.address,endpoint.port),3000)
                    connection.soTimeout = 3000; connection.tcpNoDelay = true
                    phase = "tls"
                    connection.startHandshake()
                    phase = "pair"
                    val output = DataOutputStream(connection.outputStream)
                    val input = DataInputStream(connection.inputStream)
                    AvatarStreamProtocol.writePairing(output,pairing)
                    AvatarStreamProtocol.acceptPairingReply(connection.inputStream.read())
                    if (!active(request)) break
                    pairingStore.remember(endpoint,pairing)
                    connection.soTimeout = 3000
                    publish(request,"PC paired · Waiting for a confirmed video image",true)
                    Log.i(TAG,"Paired encrypted webcam; TLS=${connection.session.protocol}")
                    retry = 0
                    var sequence = 0L
                    var lastRecordAt = SystemClock.uptimeMillis()
                    var waitingForImage = true
                    var sentImages = 0L
                    while (active(request)) {
                        val now = SystemClock.uptimeMillis()
                        val noImage = synchronized(frameLock) {
                            if (clearPending || AvatarStreamTiming.noImageDue(lastOfferedAt,lastRecordAt,now,imageInvalid)) {
                                clearPending = false; true
                            } else false
                        }
                        if (noImage) {
                            phase = "no_image_write"
                            val record = sequence++
                            writeRecord(connection) { AvatarStreamProtocol.writeNoImage(output,record,now) }
                            AvatarStreamProtocol.acceptFrameReply(input,record)
                            lastRecordAt = SystemClock.uptimeMillis()
                            if (!waitingForImage) {
                                waitingForImage = true
                                publish(request,"Paired securely · Waiting for live avatar camera",true)
                                Log.i(TAG,"Camera image unavailable; pairing retained with blank controls")
                            }
                            continue
                        }
                        val frame = synchronized(frameLock) { latest.also { latest = null } }
                        if (frame == null) { Thread.sleep(10); continue }
                        try {
                            if (!AvatarStreamProtocol.fresh(frame.capturedAt,SystemClock.uptimeMillis())) continue
                            val bytes = ByteArrayOutputStream()
                            phase = "encode"
                            check(frame.bitmap.compress(Bitmap.CompressFormat.JPEG,budget.jpegQuality,bytes)) { "Could not encode avatar frame" }
                            val jpeg = bytes.toByteArray()
                            if (!AvatarStreamProtocol.fresh(frame.capturedAt,SystemClock.uptimeMillis())) continue
                            // Socket writes have no platform timeout. Bound a blocked writer to avoid indefinitely retaining old video.
                            phase = "write"
                            val record = sequence++; val writeStarted = SystemClock.uptimeMillis()
                            writeRecord(connection) { AvatarStreamProtocol.writeFrame(output,record,frame.capturedAt,jpeg) }
                            val received = AvatarStreamProtocol.acceptFrameReply(input,record)
                            budget.network(SystemClock.uptimeMillis()-writeStarted)
                            if (received != 1) {
                                waitingForImage = true
                                publish(request,"PC receiving · Delayed picture discarded",true)
                                continue
                            }
                            lastRecordAt = SystemClock.uptimeMillis(); sentImages++
                            if (waitingForImage) {
                                waitingForImage = false
                                publish(request,"Live avatar video · Receipt confirmed by PC",true)
                                Log.i(TAG,"Camera images resumed on existing pairing")
                            }
                            if (sentImages == 1L || sentImages % 150L == 0L)
                                Log.i(TAG,"Avatar images sent=$sentImages; sourceAgeMs=${lastRecordAt-frame.capturedAt}")
                        } finally { frame.bitmap.recycle() }
                    }
                } catch (error: Exception) {
                    if (!active(request)) break
                    diagnostic("connection_failure phase=$phase type=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName ?: "none"}")
                    discardFrame()
                    if (AvatarStreamProtocol.permanentFailure(error)) {
                        synchronized(this) {
                            if (active(request)) {
                                running = false
                                status("Webcam pairing stopped: ${error.message ?: "PC identity could not be verified"}",false)
                            }
                        }
                    } else {
                        publish(request,"Connection lost · Reconnecting securely…",true)
                        Thread.sleep((1000L shl retry.coerceAtMost(2)).coerceAtMost(4000L))
                        retry++
                    }
                } finally {
                    runCatching { connection.close() }
                    if (socket === connection) socket = null
                }
            }
            synchronized(this) { if (generation == request) { running = false; discardFrame() } }
        }
    }

    /** Capture time comes from the frame source, rather than the later socket write. */
    fun offer(frame: Bitmap, capturedAt: Long) {
        synchronized(frameLock) {
            if (!running || !AvatarStreamProtocol.fresh(capturedAt,SystemClock.uptimeMillis())) return
            val copy = frame.copy(Bitmap.Config.ARGB_8888,false) ?: return
            lastOfferedAt = capturedAt; clearPending = false; imageInvalid = false
            latest?.bitmap?.recycle(); latest = Frame(copy,capturedAt)
        }
    }
    private fun active(request: Long) = running && !closed && generation == request
    private fun writeRecord(connection: SSLSocket, operation: () -> Unit) {
        val write = AvatarWriteDeadline()
        val deadline = deadlines.schedule({
            if (write.claimTimeout()) { diagnostic("write_timeout"); runCatching { connection.close() } }
        },AvatarStreamTiming.WRITE_TIMEOUT_MS,TimeUnit.MILLISECONDS)
        try { operation() } finally { write.complete(); deadline.cancel(false) }
    }
    /** Clear pixels without revoking the user's foreground pairing request. */
    fun clearPreview() {
        synchronized(frameLock) {
            latest?.bitmap?.recycle(); latest = null
            if (running) { clearPending = true; imageInvalid = true }
        }
    }
    @Synchronized private fun diagnostic(event: String) {
        val now = SystemClock.uptimeMillis()
        if (lastDiagnosticAt == 0L || now-lastDiagnosticAt >= 5000L) {
            lastDiagnosticAt = now; Log.w(TAG,event)
        }
    }
    @Synchronized private fun publish(request: Long, message: String, sharing: Boolean) {
        if (active(request)) status(message,sharing)
    }
    private fun discardFrame() { synchronized(frameLock) { latest?.bitmap?.recycle(); latest = null } }
    @Synchronized fun stop() {
        generation++; running = false
        runCatching { socket?.close() }; socket = null; discardFrame()
        status("Webcam sharing is off",false)
    }
    @Synchronized override fun close() { closed = true; stop(); executor.shutdownNow(); deadlines.shutdownNow() }
    companion object { private const val TAG = "AlterEgoWebcam" }
}
