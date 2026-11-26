package dev.viewgo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.media.projection.MediaProjectionConfig

class MainActivity : Activity() {

    private lateinit var codeField: EditText
    private lateinit var qualityField: Spinner
    private lateinit var shareField: Spinner
    private lateinit var audioField: Spinner
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView
    private var selectedHost: String? = null
    private var selectedPort: Int = 47810
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLog = object : Runnable {
        override fun run() {
            logView.text = AppLog.snapshot().takeLast(40).joinToString("\n")
            handler.postDelayed(this, 500)
        }
    }

    private var pending: Pending? = null

    private data class Pending(
        val host: String,
        val port: Int,
        val code: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val audio: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "ViewGo"
            textSize = 28f
        }
        val intro = TextView(this).apply {
            text = "Start the receiver on your laptop, tap Find laptop, then enter its code. " +
                "Both devices must be on the same Wi-Fi."
            textSize = 15f
        }
        val audioHint = TextView(this).apply {
            text = "Audio is best effort: Android and some protected/DRM apps block capture."
            textSize = 13f
        }

        val discover = Button(this).apply {
            text = "Find laptop"
            setOnClickListener { discoverReceiver() }
        }
        codeField = EditText(this).apply {
            hint = "Pairing code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        qualityField = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("1080p — 12 Mbps", "720p — 6 Mbps"),
            )
        }
        shareField = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if (Build.VERSION.SDK_INT >= 34) {
                    listOf("Share the whole screen", "Share one app")
                } else {
                    listOf("Share the whole screen")
                },
            )
        }
        audioField = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Internal audio on", "Internal audio off"),
            )
        }

        val connect = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestCapture() }
        }
        val stop = Button(this).apply {
            text = "Stop mirroring"
            setOnClickListener {
                val intent = Intent(this@MainActivity, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)
                startService(intent)
            }
        }
        statusLabel = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.START
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        val shareLog = Button(this).apply {
            text = "Share log"
            setOnClickListener {
                val log = AppLog.snapshot().joinToString("\n")
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, log)
                startActivity(Intent.createChooser(send, "Share ViewGo log"))
            }
        }

        listOf(title, intro, discover, codeField, qualityField, shareField, audioField, audioHint, connect, stop, statusLabel).forEach {
            root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(TextView(this).apply {
            text = "\n1080p is best effort. If the laptop warns about the stream or the phone gets hot, switch to 720p."
            textSize = 13f
        })
        root.addView(TextView(this).apply {
            text = "\nPhone log (also printed on the laptop while connected)"
            textSize = 13f
        })
        root.addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(shareLog, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    private fun requestCapture() {
        val host = selectedHost
        val code = normalizeCode(codeField.text.toString())
        if (host == null) {
            statusLabel.text = "Tap Find laptop first."
            AppLog.line("No receiver selected")
            return
        }
        if (code == null) {
            statusLabel.text = "The pairing code has 9 letters or digits."
            AppLog.line("Pairing code is not 9 letters or digits")
            return
        }
        val quality = qualityField.selectedItemPosition
        val audio = audioField.selectedItemPosition == 0
        pending = Pending(
            host = host,
            port = selectedPort,
            code = code,
            width = if (quality == 0) 1080 else 720,
            height = if (quality == 0) 1920 else 1280,
            bitrate = if (quality == 0) 12_000_000 else 6_000_000,
            audio = audio,
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        val shareApp = Build.VERSION.SDK_INT >= 34 && shareField.selectedItemPosition == 1
        AppLog.line(
            if (shareApp) "Requesting permission to share one app"
            else "Requesting screen and playback capture permission"
        )
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            val config = if (shareApp) {
                MediaProjectionConfig.createConfigForUserChoice()
            } else {
                MediaProjectionConfig.createConfigForDefaultDisplay()
            }
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(captureIntent, REQUEST_CAPTURE)
    }

    @Deprecated("Plain Activity result API keeps the MVP free of AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val job = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            statusLabel.text = "Screen capture was not allowed."
            AppLog.line("Screen capture was not allowed")
            return
        }
        val intent = Intent(this, MirrorService::class.java).apply {
            putExtra(MirrorService.EXTRA_HOST, job.host)
            putExtra(MirrorService.EXTRA_PORT, job.port)
            putExtra(MirrorService.EXTRA_CODE, job.code)
            putExtra(MirrorService.EXTRA_WIDTH, job.width)
            putExtra(MirrorService.EXTRA_HEIGHT, job.height)
            putExtra(MirrorService.EXTRA_BITRATE, job.bitrate)
            putExtra(MirrorService.EXTRA_AUDIO, job.audio)
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        statusLabel.text = "Starting mirroring…"
        AppLog.line("Capture approved, starting the service")
        moveTaskToBack(true)
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshLog)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refreshLog)
    }

    private fun discoverReceiver() {
        selectedHost = null
        selectedPort = 47810
        statusLabel.text = "Searching local Wi-Fi…"
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        manager.discoverServices("_viewgo._tcp.", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                manager.stopServiceDiscovery(this)
                runOnUiThread { statusLabel.text = "Wi-Fi discovery failed (code $error)." }
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) { manager.stopServiceDiscovery(this) }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                manager.stopServiceDiscovery(this)
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, error: Int) {
                            runOnUiThread { statusLabel.text = "Could not resolve laptop (code $error)." }
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            selectedHost = resolved.host.hostAddress
                            selectedPort = resolved.port
                            runOnUiThread { statusLabel.text = "Found laptop: ${resolved.serviceName}" }
                            AppLog.line("Local discovery found ${resolved.serviceName}")
                        }
                    })
                } catch (error: UnsupportedOperationException) {
                    runOnUiThread { statusLabel.text = "This Android version cannot resolve local services." }
                }
            }
        })
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }

}
