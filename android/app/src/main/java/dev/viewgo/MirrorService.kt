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
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class MirrorService : Service() {

    companion object {
        const val ACTION_STOP = "dev.viewgo.STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL = "viewgo"
        private const val NOTIFICATION_ID = 1
        private const val FPS = 30
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
    @Volatile private var rotationError: String? = null

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
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000)

        startForegroundCompat("Connecting to $host…")
        running = true
        AppLog.line("Connecting to $host:$port")
        AppLog.attach { text -> runCatching { link?.send(Proto.T_LOG, text.toByteArray()) } }

        worker = Thread {
            try {
                val connection = Socket()
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

                val manager = getSystemService(MediaProjectionManager::class.java)
                val capture = manager.getMediaProjection(resultCode, resultData)
                    ?: throw IOException("Screen capture permission was lost.")
                projection = capture
                capture.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopMirroring("Screen capture stopped")
                    }
                }, Handler(Looper.getMainLooper()))

                val size = captureSize(maxWidth, maxHeight)
                var width = size.first
                var height = size.second
                var activeBitrate = bitrate
                var encoderSize = try {
                    beginEncoder(width, height, bitrate)
                    width to height
                } catch (first: Exception) {
                    if (maxWidth <= 720) throw first
                    AppLog.line("1080 encoder unavailable; retrying at 720p")
                    val fallback = captureSize(720, 1280)
                    width = fallback.first
                    height = fallback.second
                    activeBitrate = 5_000_000
                    beginEncoder(fallback.first, fallback.second, activeBitrate)
                    fallback.first to fallback.second
                }

                val captureDisplay = try {
                    capture.createVirtualDisplay(
                        "viewgo",
                        width,
                        height,
                        resources.configuration.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoderSurface,
                        null,
                        null,
                    ) ?: throw IOException("Android could not create the capture display")
                } catch (first: Exception) {
                    if (encoderSize.first <= 720) throw first
                    runCatching { encoder?.stop() }
                    runCatching { encoder?.release() }
                    encoder = null
                    runCatching { encoderSurface?.release() }
                    encoderSurface = null
                    val fallback = captureSize(720, 1280)
                    activeBitrate = 5_000_000
                    val surface = beginEncoder(fallback.first, fallback.second, activeBitrate)
                    encoderSurface = surface
                    width = fallback.first
                    height = fallback.second
                    encoderSize = width to height
                    capture.createVirtualDisplay(
                        "viewgo", width, height, resources.configuration.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
                    ) ?: throw IOException("Android could not create the 720p capture display")
                }
                virtualDisplay = captureDisplay
                val configWidth = encoderSize.first
                val configHeight = encoderSize.second
                val initialSize = encoderSize
                updateNotification("Mirroring $width×$height")
                AppLog.line("Mirroring ${initialSize.first}×${initialSize.second} at ${activeBitrate / 1_000_000} Mbps")

                val audio = startAudioCapture(capture, session)
                sendVideoConfig(session, initialSize.first, initialSize.second)
                drainEncoder(session, captureDisplay, maxWidth, maxHeight, configWidth, configHeight, activeBitrate, audio)
            } catch (error: Throwable) {
                AppLog.line("Stopped: ${error.message ?: error.javaClass.simpleName}")
                if (running) stopMirroring("Stopped: ${error.message ?: error.javaClass.simpleName}")
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
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = input
            return input
        } catch (error: Exception) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw error
        }
    }

    private fun drainEncoder(
        session: Link,
        captureDisplay: VirtualDisplay,
        maxWidth: Int,
        maxHeight: Int,
        configWidth: Int,
        configHeight: Int,
        bitrate: Int,
        audio: AudioRecord?,
    ) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var csd: ByteArray? = null
        val audioThread = audio?.let { record ->
            Thread {
                val chunk = ByteArray(1_920) // 20 ms PCM chunks keep A/V interleave bounded.
                while (running) {
                    val count = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
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

        var orientation = currentRotation()
        while (running) {
            val nextOrientation = currentRotation()
            if (nextOrientation != orientation) {
                orientation = nextOrientation
                val size = captureSize(maxWidth, maxHeight)
                val isLandscape = orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270
                if (isLandscape != (configWidth > configHeight)) {
                    rotationError = "Orientation changed; reconnect to restart the encoder in ${size.first}×${size.second}"
                    stopMirroring(rotationError!!)
                    break
                }
                captureDisplay.resize(size.first, size.second, resources.configuration.densityDpi)
                AppLog.line("Phone orientation updated: ${size.first}×${size.second}")
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
                    session.send(Proto.T_VIDEO_FRAME, payload)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
        audioThread?.interrupt()
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
        if (rotationError == null) runCatching { codec.signalEndOfInputStream() }
    }

    private fun captureSize(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val point = Point()
        display.getRealSize(point)
        val rawW = point.x.coerceAtLeast(1)
        val rawH = point.y.coerceAtLeast(1)
        val rawLong = maxOf(rawW, rawH)
        val rawShort = minOf(rawW, rawH)
        val targetLong = maxOf(maxWidth, maxHeight)
        val targetShort = minOf(maxWidth, maxHeight)
        val scale = minOf(1f, targetLong.toFloat() / rawLong, targetShort.toFloat() / rawShort)
        var w = (rawW * scale).toInt().coerceAtLeast(2)
        var h = (rawH * scale).toInt().coerceAtLeast(2)
        w -= w % 2
        h -= h % 2
        return w to h
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation

    private fun sendVideoConfig(session: Link, width: Int, height: Int) {
        session.send(Proto.T_VIDEO_CONFIG, ByteArray(5).apply {
            putShort(this, 0, width)
            putShort(this, 2, height)
            this[4] = FPS.toByte()
        })
    }

    private fun startAudioCapture(projection: MediaProjection, session: Link): AudioRecord? = try {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(48_000 * 2 * 2 / 5)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Android playback capture could not initialize")
        }
        audioRecord = record
        session.send(Proto.T_AUDIO_CONFIG, byteArrayOf(0, 0, 0xBB.toByte(), 0x80.toByte(), 2))
        record.startRecording()
        AppLog.line("Internal playback audio capture started (some apps block capture)")
        record
    } catch (error: Exception) {
        AppLog.line("Internal audio unavailable: ${error.message ?: error.javaClass.simpleName}")
        null
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

    private fun stopMirroring(reason: String) {
        if (!running) return
        running = false
        AppLog.line(reason)
        updateNotification(reason)
        if (rotationError == null) runCatching { encoder?.signalEndOfInputStream() }
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
        rotationError = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
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
