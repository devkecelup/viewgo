package dev.viewgo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

// Palette dealt by design-taste-frontend (`start.mjs brand --page software --bg #fff
// --accent #0f7b4f --radius soft`): white ground, green action, radius soft.
private const val C_BG = "#FFFFFF"
private const val C_SURFACE = "#F1F2F3"
private const val C_BORDER = "#E4E7EB"
private const val C_INK = "#101828"
private const val C_MUTED = "#5F6772"
private const val C_ACCENT = "#0F7B4F"
private const val C_ERROR = "#B42318"

class MainActivity : Activity() {

    private lateinit var codeField: EditText
    private lateinit var qualityField: Spinner
    private lateinit var shareField: Spinner
    private lateinit var audioField: Spinner
    private lateinit var statusLabel: TextView
    private lateinit var laptopStatus: TextView
    private var selectedHost: String? = null
    private var selectedPort: Int = 47810

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun card(vertical: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor(C_BG))
            setStroke(dp(1), Color.parseColor(C_BORDER))
        }
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun stepHead(iconRes: Int, step: String, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(
                icon,
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "$step  $title"
                    textSize = 15f
                    setTextColor(Color.parseColor(C_INK))
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                },
            )
        }

    private fun optionRow(iconRes: Int, label: String, spinner: Spinner): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor(C_INK))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                },
            )
            addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun paintButton(button: Button, filled: Boolean, iconRes: Int) {
        button.gravity = Gravity.CENTER
        button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        button.compoundDrawablePadding = dp(8)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (filled) {
                setColor(Color.parseColor(C_ACCENT))
                setStroke(0, Color.TRANSPARENT)
            } else {
                setColor(Color.parseColor(C_BG))
                setStroke(dp(1), Color.parseColor(C_ACCENT))
            }
        }
        button.setTextColor(
            if (filled) Color.WHITE else Color.parseColor(C_ACCENT),
        )
        button.typeface = Typeface.DEFAULT_BOLD
        button.textSize = 16f
        button.setPadding(dp(16), dp(13), dp(16), dp(13))
        button.elevation = if (filled) dp(2).toFloat() else 0f
    }

    private fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.setTextColor(Color.parseColor(if (error) C_ERROR else C_MUTED))
    }

    private fun addCard(root: LinearLayout, view: View) {
        root.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val spacer = View(this)
        root.addView(spacer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_logo) },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "ViewGo"
                            textSize = 26f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor(C_INK))
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Mirror your phone to your laptop over Wi-Fi"
                            textSize = 14f
                            setTextColor(Color.parseColor(C_MUTED))
                        },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                },
            )
        }
        root.addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val gap = View(this)
        root.addView(gap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        // Step 1: find the laptop
        val laptopCard = card().apply {
            addView(stepHead(R.drawable.ic_laptop, "1", "Find your laptop"))
            laptopStatus = TextView(this@MainActivity).apply {
                text = "Not found yet. Start the receiver on your laptop first."
                textSize = 14f
                setTextColor(Color.parseColor(C_MUTED))
                setPadding(0, dp(8), 0, dp(10))
            }
            addView(laptopStatus)
            val find = Button(this@MainActivity).apply {
                text = "Find laptop"
                setOnClickListener { discoverReceiver() }
            }
            paintButton(find, true, R.drawable.ic_search)
            addView(find, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addCard(root, laptopCard)

        // Step 2: pairing code
        val codeCard = card().apply {
            addView(stepHead(R.drawable.ic_key, "2", "Enter the pairing code"))
            codeField = EditText(this@MainActivity).apply {
                hint = "e.g. H5T2"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(4))
                setSingleLine()
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.3f
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(Color.parseColor(C_INK))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor(C_SURFACE))
                    setStroke(dp(1), Color.parseColor(C_BORDER))
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            addView(codeField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                TextView(this@MainActivity).apply {
                    text = "Four characters, shown on your laptop."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
        addCard(root, codeCard)

        // Step 3: options
        qualityField = spinnerFor(listOf("1080p - 16 Mbps", "720p - 6 Mbps"))
        shareField = spinnerFor(
            if (Build.VERSION.SDK_INT >= 34) {
                listOf("Whole screen", "One app")
            } else {
                listOf("Whole screen")
            },
        )
        audioField = spinnerFor(listOf("Internal audio on", "Internal audio off"))
        val optionsCard = card().apply {
            addView(stepHead(R.drawable.ic_settings, "3", "Options"))
            val rows = listOf(
                optionRow(R.drawable.ic_gauge, "Quality", qualityField),
                optionRow(R.drawable.ic_apps, "Share", shareField),
                optionRow(R.drawable.ic_volume, "Audio", audioField),
            )
            rows.forEachIndexed { index, row ->
                addView(
                    row,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                if (index != rows.lastIndex) {
                    val divider = View(this@MainActivity).apply {
                        setBackgroundColor(Color.parseColor(C_BORDER))
                    }
                    addView(
                        divider,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                    )
                }
            }
            addView(
                TextView(this@MainActivity).apply {
                    text = "1080p is best effort. If the phone gets hot, use 720p."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }
        addCard(root, optionsCard)

        // Actions
        val connect = Button(this).apply {
            text = "Start mirroring"
            setOnClickListener { requestCapture() }
        }
        paintButton(connect, true, R.drawable.ic_play)
        root.addView(connect, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val stop = Button(this).apply {
            text = "Stop mirroring"
            setOnClickListener {
                val intent = Intent(this@MainActivity, MirrorService::class.java)
                    .setAction(MirrorService.ACTION_STOP)
                startService(intent)
                setStatus("Stopped.")
            }
        }
        paintButton(stop, false, R.drawable.ic_stop)
        root.addView(stop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        statusLabel = TextView(this).apply {
            text = "Start the receiver on your laptop, then find it here."
            textSize = 14f
            setTextColor(Color.parseColor(C_MUTED))
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(statusLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply { addView(root) }
        // Content must clear the status bar: with targetSdk 35 Android 15 draws edge-to-edge, so
        // without this the header sits under the battery/clock and Stop can hide behind the nav bar.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(dp(20), dp(18) + top, dp(20), dp(24) + bottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    private fun spinnerFor(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items,
        )
    }

    private fun requestCapture() {
        val host = selectedHost
        val code = normalizeCode(codeField.text.toString())
        if (host == null) {
            setStatus("Find your laptop first.", error = true)
            laptopStatus.setTextColor(Color.parseColor(C_ERROR))
            return
        }
        if (code == null) {
            setStatus("The pairing code has 4 letters or digits.", error = true)
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
            bitrate = if (quality == 0) 16_000_000 else 6_000_000,
            audio = audio,
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        val shareApp = Build.VERSION.SDK_INT >= 34 && shareField.selectedItemPosition == 1
        AppLog.line(
            if (shareApp) "Requesting permission to share one app"
            else "Requesting screen and playback capture permission",
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
        setStatus("Allow screen capture in the system dialog.")
        startActivityForResult(captureIntent, REQUEST_CAPTURE)
    }

    @Deprecated("Plain Activity result API keeps the MVP free of AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val job = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen capture was not allowed.", error = true)
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
        setStatus("Mirroring started. You can leave this app.")
        AppLog.line("Capture approved, starting the service")
        moveTaskToBack(true)
    }

    private fun discoverReceiver() {
        selectedHost = null
        selectedPort = 47810
        laptopStatus.setTextColor(Color.parseColor(C_MUTED))
        laptopStatus.text = "Searching your Wi-Fi for the laptop..."
        setStatus("Searching local Wi-Fi...")
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        manager.discoverServices("_viewgo._tcp.", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                manager.stopServiceDiscovery(this)
                runOnUiThread {
                    laptopStatus.text = "Wi-Fi discovery failed (code $error)."
                    setStatus("Wi-Fi discovery failed.", error = true)
                }
            }

            override fun onStopDiscoveryFailed(type: String, error: Int) = manager.stopServiceDiscovery(this)
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                manager.stopServiceDiscovery(this)
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, error: Int) {
                            runOnUiThread {
                                laptopStatus.text = "Could not resolve laptop (code $error)."
                                setStatus("Could not resolve the laptop.", error = true)
                            }
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            selectedHost = resolved.host.hostAddress
                            selectedPort = resolved.port
                            runOnUiThread {
                                laptopStatus.setTextColor(Color.parseColor(C_ACCENT))
                                laptopStatus.text = "Connected to ${resolved.serviceName}. Now enter the code."
                                setStatus("Laptop found. Enter the pairing code.")
                            }
                            AppLog.line("Local discovery found ${resolved.serviceName}")
                        }
                    })
                } catch (error: UnsupportedOperationException) {
                    runOnUiThread {
                        laptopStatus.text = "This Android version cannot resolve local services."
                        setStatus("Discovery unavailable on this Android version.", error = true)
                    }
                }
            }
        })
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }

}