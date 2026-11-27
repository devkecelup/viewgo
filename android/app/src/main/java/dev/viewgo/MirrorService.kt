package dev.viewgo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Point
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class MirrorService : Service() {

    companion object {
        const val ACTION_STOP = "dev.viewgo.STOP"
        const val ACTION_STATE = "dev.viewgo.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ERROR = "error"
        const val STATE_CONNECTING = "connecting"
        const val STATE_LIVE = "live"
        const val STATE_STOPPED = "stopped"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_AUDIO = "audio"

        private const val CHANNEL = "viewgo"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_FPS = 30
        private const val SERVICE_TYPE_MEDIA_PROJECTION = 0x00000020
        private const val SERVICE_TYPE_MEDIA_PLAYBACK = 0x00000002
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var socket: Socket? = null
    private var link: Link? = null
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var audioRecord: AudioRecord? = null
    // Bounded video queue: at most 2 frames in flight. When the network is slower than the
    // encoder the oldest frame is dropped instead of queued, so delay cannot grow.
    private val videoQueue = ArrayBlockingQueue<ByteArray>(2)
    private var needSync = false
    private var lastSyncRequest = 0L
    private var droppedFrames = 0L
    private var lastDropReport = 0L
    /**
     * Adaptive bitrate. The encoder target follows what the socket actually carries: the sender
     * thread lowers it when writes block, the encode loop applies it. Screen content rarely needs
     * the full 16 Mbps, so this keeps the picture moving on a weak link instead of filling the
     * send buffer until frames are dropped.
     */
    @Volatile
    private var videoBitrate = 0
    private var bitrateTarget = 0
    private var appliedBitrate = 0
    private var lastBitrateCut = 0L
    private var lastEncoderReport = 0L
    private var lastEncodeReport = 0L
    private var rotationAttempts = 0
    /** Encoder frame rate target for this session: 30 by default, 60 when the user asks for it. */
    private var targetFps = DEFAULT_FPS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirroring("Stopped")
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST)
        val code = intent?.getStringExtra(EXTRA_CODE)
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (host.isNullOrEmpty() || code == null || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent.getIntExtra(EXTRA_PORT, 47810)
        val maxWidth = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val maxHeight = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        // Capture at 60 fps only when asked: it is the encoder's heaviest setting and the laptop
        // can only show what the phone encodes.
        targetFps = intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS).coerceIn(15, 60)

        intentAudioRequested = intent.getBooleanExtra(EXTRA_AUDIO, true)
        startForegroundCompat("Connecting to $host…")
        broadcastState(STATE_CONNECTING, "Connecting to $host:$port…")
        running = true
        AppLog.line("Connecting to $host:$port")
        AppLog.attach { text -> runCatching { link?.send(Proto.T_LOG, text.toByteArray()) } }

        worker = Thread {
            try {
                val connection = Socket()
                // Bound the in-flight video data so a slow link drops frames (the encoder stalls)
                // instead of queueing them and growing the mirror delay. 128 KB ≈ 64 ms at 16 Mbps
                // and is far more than a LAN needs for full throughput.
                runCatching { connection.sendBufferSize = 128 * 1024 }
                connection.connect(InetSocketAddress(host, port), 8000)
                connection.tcpNoDelay = true
                connection.soTimeout = 20_000
                socket = connection

                val session = Link(connection, code)
                session.handshake()
                connection.soTimeout = 0
                link = session
                AppLog.line("Paired with $host")
                updateNotification("Paired. Starting capture…")
                broadcastState(STATE_LIVE, "Mirroring started. You can leave this app.")

                val manager = getSystemService(MediaProjectionManager::class.java)
                val capture = manager.getMediaProjection(resultCode, resultData)
                    ?: throw IOException("Screen capture permission was lost.")
                projection = capture
                val audioRequested = intentAudioRequested
                capture.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopMirroring("Screen capture stopped")
                    }
                }, Handler(Looper.getMainLooper()))

                mirror(session, capture, maxWidth, maxHeight, bitrate, audioRequested)
            } catch (error: Throwable) {
                AppLog.line("Stopped: ${error.message ?: error.javaClass.simpleName}")
                if (running) stopMirroring("Stopped: ${error.message ?: error.javaClass.simpleName}", error = true)
            } finally {
                releaseAll()
            }
        }.also { it.start() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        AppLog.attach(null)
        runCatching { link?.sendStop() }
        runCatching { link?.close() }
        runCatching { socket?.close() }
        releaseAll()
        super.onDestroy()
    }

    private fun beginEncoder(width: Int, height: Int, bitrate: Int): Surface {
        fun format(profile: Int?): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // Screen mirroring needs the lowest possible delay, not compression efficiency:
                // priority 0 tells the encoder to trade quality for latency, and zero B-frames
                // removes the reorder delay (High profile would otherwise allow them).
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // High profile squeezes more detail per bitrate for screen content; some
                // encoders reject it, so the plain format below is the fallback.
                if (profile != null) setInteger(MediaFormat.KEY_PROFILE, profile)
            }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            try {
                codec.configure(
                    format(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            } catch (rejected: Exception) {
                codec.configure(format(null), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val input = codec.createInputSurface()
            codec.start()
            // Ask for an IDR straight away so a rebuilt encoder does not leave the receiver blank.
            runCatching {
                codec.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
            encoder = codec
            encoderSurface = input
            return input
        } catch (error: Exception) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw error
        }
    }

    private class Opened(
        val display: VirtualDisplay,
        val codec: MediaCodec,
        val width: Int,
        val height: Int,
    )

    private fun mirror(
        session: Link,
        projection: MediaProjection,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedBitrate: Int,
        audioRequested: Boolean,
    ) {
        var maxWidth = requestedWidth
        var maxHeight = requestedHeight
        var bitrate = requestedBitrate

        var opened = try {
            val size = captureSize(maxWidth, maxHeight)
            open(projection, size.first, size.second, bitrate)
        } catch (first: Throwable) {
            releaseEncoder()
            if (minOf(maxWidth, maxHeight) <= 720) throw first
            AppLog.line("1080p capture unavailable; retrying at 720p (${first.message ?: first.javaClass.simpleName})")
            maxWidth = 720
            maxHeight = 1280
            bitrate = 5_000_000
            val size = captureSize(maxWidth, maxHeight)
            open(projection, size.first, size.second, bitrate)
        }
        updateNotification("Mirroring ${opened.width}×${opened.height}")
        AppLog.line("Mirroring ${opened.width}×${opened.height} at ${bitrate / 1_000_000} Mbps")
        // Start from the requested rate and let the sender thread adapt it to the link.
        bitrateTarget = bitrate
        videoBitrate = bitrate
        appliedBitrate = bitrate

        val audio = if (audioRequested) startAudioCapture(projection, session) else null
        val audioThread = startAudioThread(session, audio)
        videoQueue.clear()
        needSync = false
        val senderThread = startVideoSender(session)
        sendVideoConfig(session, opened.width, opened.height)
        var csd = runCatching { readCsd(opened.codec.outputFormat) }.getOrNull()
        var orientation = currentRotation()
        var info = MediaCodec.BufferInfo()

        // Encoder output rate: when the phone cannot encode the capture size in real time the
        // stream is slow no matter how good the network is, and that is worth saying once instead
        // of leaving it a mystery.
        var encoded = 0
        var encodeWindow = SystemClock.elapsedRealtime()
        fun noteEncoded() {
            encoded++
            val now = SystemClock.elapsedRealtime()
            if (now - encodeWindow < 1_000) return
            val fps = encoded * 1_000 / (now - encodeWindow)
            if (fps < targetFps - 5 && now - lastEncoderReport >= 5_000) {
                lastEncoderReport = now
                AppLog.line("encoder $fps fps (target $targetFps): ${opened.width}×${opened.height} is heavy for this phone")
            }
            encoded = 0
            encodeWindow = now
        }

        try {
            while (running) {
                val nextOrientation = currentRotation()
                if (nextOrientation != orientation) {
                    val size = captureSize(maxWidth, maxHeight)
                    AppLog.line("Phone rotated; rebuilding the encoder for ${size.first}×${size.second}")
                    try {
                        opened = rebuild(opened, size.first, size.second, videoBitrate)
                        // Commit the orientation only after the swap succeeded, otherwise the
                        // retry below never happens and the session stays portrait while the
                        // screen is landscape — a black/squeezed picture that never recovers.
                        orientation = nextOrientation
                        rotationAttempts = 0
                        info = MediaCodec.BufferInfo()
                        csd = runCatching { readCsd(opened.codec.outputFormat) }.getOrNull()
                        sendVideoConfig(session, opened.width, opened.height)
                        appliedBitrate = videoBitrate
                        updateNotification("Mirroring ${opened.width}×${opened.height}")
                    } catch (error: Throwable) {
                        rotationAttempts++
                        if (rotationAttempts >= 5) {
                            // Bound the retries so a permanent failure cannot spin the encoder.
                            orientation = nextOrientation
                            rotationAttempts = 0
                            AppLog.line(
                                "Rotation kept failing; staying at ${opened.width}×${opened.height}",
                            )
                        } else {
                            AppLog.line(
                                "Rotation swap failed (${error.message ?: error.javaClass.simpleName}); retrying",
                            )
                            runCatching { Thread.sleep(50) }
                        }
                    }
                    continue
                }

                val codec = opened.codec
                if (appliedBitrate != videoBitrate) {
                    appliedBitrate = videoBitrate
                    runCatching {
                        codec.setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, videoBitrate)
                        })
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    csd = readCsd(codec.outputFormat)
                    continue
                }
                if (index < 0) continue
                try {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        var nal = toAnnexB(buffer, info.offset, info.size)
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (keyframe && csd != null) nal = csd + nal
                        val payload = ByteArray(9 + nal.size)
                        payload[0] = if (keyframe) 1 else 0
                        putLong(payload, 1, info.presentationTimeUs)
                        System.arraycopy(nal, 0, payload, 9, nal.size)
                        reportEncodeDelay(info.presentationTimeUs)
                        noteEncoded()
                        if (keyframe) needSync = false
                        if (needSync) {
                            // Waiting for the IDR requested after a drop: sending a P-frame that
                            // references a dropped one would corrupt the picture until recovery.
                            noteDropped(codec)
                        } else if (!videoQueue.offer(payload)) {
                            videoQueue.poll()
                            videoQueue.offer(payload)
                            needSync = true
                            noteDropped(codec)
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            senderThread.interrupt()
            runCatching { senderThread.join(500) }
            videoQueue.clear()
            stopAudio(audio, audioThread)
        }
    }

    /** Opens an encoder and a virtual display of the exact size, releasing both if either fails. */
    private fun open(projection: MediaProjection, width: Int, height: Int, bitrate: Int): Opened {
        val surface = beginEncoder(width, height, bitrate)
        val codec = encoder ?: throw IOException("Encoder did not start")
        return try {
            val display = projection.createVirtualDisplay(
                "viewgo",
                width,
                height,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            ) ?: throw IOException("Android could not create the capture display")
            virtualDisplay = display
            Opened(display, codec, width, height)
        } catch (error: Throwable) {
            releaseEncoder()
            throw error
        }
    }

    /**
     * MediaCodec dimensions are fixed once started, but Android only allows one
     * createVirtualDisplay() per MediaProjection: the display is kept and only resized and
     * re-surfaced onto a new encoder.
     *
     * Order matters: the display is detached from the old surface *first*. Swapping while the
     * display is still rendering into a codec that is about to stop is what makes the handover
     * fail on real devices (Android rejects it and the session keeps portrait dims while the
     * screen is landscape, which shows as a black/squeezed frame).
     */
    private fun rebuild(
        old: Opened,
        width: Int,
        height: Int,
        bitrate: Int,
    ): Opened {
        val oldSurface = encoderSurface

        // 1. Detach so nothing renders into the codec we are about to stop.
        runCatching { old.display.setSurface(null) }

        // 2. New encoder first, so an allocation failure can still reattach the old one.
        val surface = try {
            beginEncoder(width, height, bitrate)
        } catch (error: Throwable) {
            runCatching { old.display.setSurface(oldSurface) }
            throw error
        }
        val codec = encoder ?: throw IOException("Encoder did not start")

        // 3. Point the display at the new size and surface.
        try {
            old.display.resize(width, height, resources.configuration.densityDpi)
            old.display.setSurface(surface)
        } catch (error: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            encoder = old.codec
            encoderSurface = oldSurface
            runCatching { old.display.setSurface(oldSurface) }
            throw error
        }

        // 4. Only now is the old codec unreachable and safe to release.
        runCatching { old.codec.signalEndOfInputStream() }
        runCatching { old.codec.stop() }
        runCatching { old.codec.release() }
        runCatching { oldSurface?.release() }
        virtualDisplay = old.display
        return Opened(old.display, codec, width, height)
    }

    private fun startAudioThread(session: Link, record: AudioRecord?): Thread? = record?.let {
        Thread {
            val chunk = ByteArray(1_920) // 20 ms PCM chunks keep A/V interleave bounded.
            while (running) {
                val count = runCatching {
                    record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                }.getOrDefault(0)
                if (count > 0) {
                    try {
                        session.send(Proto.T_AUDIO_FRAME, chunk.copyOf(count))
                    } catch (error: Exception) {
                        AppLog.line("Audio stream stopped: ${error.message}")
                        break
                    }
                }
            }
        }.also { it.start() }
    }

    private fun stopAudio(record: AudioRecord?, thread: Thread?) {
        thread?.interrupt()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        if (audioRecord === record) audioRecord = null
    }

    /** Capture to encoder-output delay, clock-free (both stamps are the phone monotonic clock). */
    private fun reportEncodeDelay(presentationTimeUs: Long) {
        val nowUs = System.nanoTime() / 1000
        val ms = (nowUs - presentationTimeUs) / 1000
        if (ms < 0 || ms > 5_000) return // unexpected PTS base, do not report nonsense
        if (ms < 200) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEncodeReport < 1_000) return
        lastEncodeReport = now
        AppLog.line("encode delay ${ms} ms")
    }

    private fun noteDropped(codec: MediaCodec) {
        droppedFrames++
        val now = SystemClock.elapsedRealtime()
        if (now - lastDropReport < 1_000) return
        lastDropReport = now
        AppLog.line("dropped $droppedFrames frames (keeping delay low)")
        droppedFrames = 0
        // Ask for an IDR so the picture recovers; throttled to avoid piling keyframes onto a
        // network that is already behind.
        if (now - lastSyncRequest >= 500) {
            lastSyncRequest = now
            runCatching {
                codec.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    /** Drains the bounded queue off the encode loop so a slow socket cannot stall the encoder. */
    private fun startVideoSender(session: Link): Thread = Thread {
        var worst = 0L
        var reportAt = SystemClock.elapsedRealtime()
        while (running) {
            val frame = try {
                videoQueue.take()
            } catch (interrupted: InterruptedException) {
                break
            }
            val started = SystemClock.elapsedRealtime()
            try {
                session.send(Proto.T_VIDEO_FRAME, frame)
            } catch (error: Exception) {
                // The laptop closed the session (its Stop button, or it quit): stop capturing
                // too, otherwise the phone keeps encoding into a dead socket forever.
                AppLog.line("Video send stopped: ${error.message}")
                stopMirroring("Stopped from laptop")
                break
            }
            val took = SystemClock.elapsedRealtime() - started
            if (took > worst) worst = took
            val now = SystemClock.elapsedRealtime()
            if (now - reportAt >= 1_000) {
                if (worst >= 40) AppLog.line("send blocked $worst ms (network queue)")
                worst = 0
                reportAt = now
            }
        }
    }.also { it.start() }

    /** Short side is limited by the quality choice; the long side keeps native proportions. */
    private fun captureSize(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val point = Point()
        display.getRealSize(point)
        val rawShort = minOf(point.x, point.y).coerceAtLeast(2)
        val rawLong = maxOf(point.x, point.y).coerceAtLeast(2)
        val targetShort = minOf(maxWidth, maxHeight)
        val targetLong = if (targetShort >= 1080) 2400 else 1600
        val scale = minOf(1f, targetShort.toFloat() / rawShort, targetLong.toFloat() / rawLong)
        val shortSide = ((rawShort * scale).toInt() and -2).coerceAtLeast(2)
        val longSide = ((rawLong * scale).toInt() and -2).coerceAtLeast(2)
        val landscape = currentRotation() == Surface.ROTATION_90 || currentRotation() == Surface.ROTATION_270
        return if (landscape) longSide to shortSide else shortSide to longSide
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation

    private fun sendVideoConfig(session: Link, width: Int, height: Int) {
        session.send(Proto.T_VIDEO_CONFIG, ByteArray(5).apply {
            putShort(this, 0, width)
            putShort(this, 2, height)
            this[4] = targetFps.toByte()
        })
    }

    private fun startAudioCapture(projection: MediaProjection, session: Link): AudioRecord? = try {
        val record = createAudioRecord(projection)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Android playback capture could not initialize")
        }
        audioRecord = record
        session.send(Proto.T_AUDIO_CONFIG, byteArrayOf(0, 0, 0xBB.toByte(), 0x80.toByte(), 2))
        record.startRecording()
        AppLog.line("Internal playback capture active; Android may still block individual apps")
        record
    } catch (error: Exception) {
        AppLog.line("Internal audio unavailable: ${error.message ?: error.javaClass.simpleName}")
        null
    }

    private fun createAudioRecord(projection: MediaProjection): AudioRecord {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Android 10 or newer is required for playback capture")
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(48_000 * 2 * 2 / 5)
            .build()
    }

    private fun readCsd(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0") ?: return null
        val pps = format.getByteBuffer("csd-1") ?: return null
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(sps.toByteArray())
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(pps.toByteArray())
        return out.toByteArray()
    }

    /** Encoders either emit Annex-B or 4-byte length-prefixed NALs; the receiver expects Annex-B. */
    private fun toAnnexB(buffer: ByteBuffer, offset: Int, size: Int): ByteArray {
        val copy = buffer.duplicate()
        copy.position(offset)
        copy.limit(offset + size)
        val bytes = ByteArray(size)
        copy.get(bytes)
        val startsAnnexB = bytes.size >= 3 &&
            bytes[0].toInt() == 0 && bytes[1].toInt() == 0 &&
            (bytes[2].toInt() == 1 || (bytes.size >= 4 && bytes[2].toInt() == 0 && bytes[3].toInt() == 1))
        if (startsAnnexB) return bytes

        val out = ByteArrayOutputStream(size + 16)
        var pos = 0
        while (pos + 4 <= size) {
            val length = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            if (length <= 0 || pos + 4 + length > size) return bytes
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, pos + 4, length)
            pos += 4 + length
        }
        return out.toByteArray()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun stopMirroring(reason: String, error: Boolean = false) {
        if (!running) return
        running = false
        AppLog.line(reason)
        updateNotification(reason)
        broadcastState(STATE_STOPPED, reason, error)
        runCatching { encoder?.signalEndOfInputStream() }
        runCatching { audioRecord?.stop() }
        runCatching { link?.sendStop() }
        runCatching { link?.close() }
        runCatching { socket?.close() }
        releaseAll()
        stopForeground(true)
        stopSelf()
    }

    private fun releaseAll() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        releaseEncoder()
        runCatching { projection?.stop() }
        projection = null
    }

    private fun releaseEncoder() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    if (intentAudioRequested) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private var intentAudioRequested = true

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /** Tells the activity (if it is still alive) why the session started or ended. */
    private fun broadcastState(state: String, message: String, error: Boolean = false) {
        runCatching {
            sendBroadcast(
                Intent(ACTION_STATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_STATE, state)
                    .putExtra(EXTRA_MESSAGE, message)
                    .putExtra(EXTRA_ERROR, error),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ViewGo")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}

private fun putShort(target: ByteArray, at: Int, value: Int) {
    target[at] = ((value shr 8) and 0xFF).toByte()
    target[at + 1] = (value and 0xFF).toByte()
}

private fun putLong(target: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) target[at + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
}
