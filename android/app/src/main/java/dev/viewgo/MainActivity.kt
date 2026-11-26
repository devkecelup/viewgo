package dev.viewgo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : Activity() {

    private lateinit var hostField: EditText
    private lateinit var codeField: EditText
    private lateinit var qualityField: Spinner
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView
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
            text = "Start mirroring on the laptop, then type the pairing code it shows. " +
                "Both devices must be on the same Wi-Fi."
            textSize = 15f
        }

        hostField = EditText(this).apply {
            hint = "Laptop address, e.g. 192.168.1.10"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
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
                listOf("1080p — 6 Mbps", "720p — 3 Mbps"),
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

        listOf(title, intro, hostField, codeField, qualityField, connect, stop, statusLabel).forEach {
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
        val host = hostField.text.toString().trim().removePrefix("http://").removeSuffix("/")
        val code = normalizeCode(codeField.text.toString())
        if (host.isEmpty()) {
            statusLabel.text = "Enter the laptop address first."
            AppLog.line("No laptop address entered")
            return
        }
        if (code == null) {
            statusLabel.text = "The pairing code has 9 letters or digits."
            AppLog.line("Pairing code is not 9 letters or digits")
            return
        }
        val quality = qualityField.selectedItemPosition
        pending = Pending(
            host = host,
            port = 47810,
            code = code,
            width = if (quality == 0) 1080 else 720,
            height = if (quality == 0) 1920 else 1280,
            bitrate = if (quality == 0) 6_000_000 else 3_000_000,
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
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

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }
}
