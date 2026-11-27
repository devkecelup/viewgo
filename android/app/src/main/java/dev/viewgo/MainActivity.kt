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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    private lateinit var statusLabel: TextView
    private lateinit var laptopStatus: TextView
    private lateinit var statusDot: View
    private lateinit var primaryButton: Button
    private var selectedHost: String? = null
    private var selectedPort: Int = 47810

    // Option state — chip rows and a switch replace the old spinners.
    private var qualityIndex = 0
    private var shareIndex = 0
    private var audioOn = true
    private var mirroring = false

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

    /** Timeline head: a numbered green circle, the step title, and the step's glyph on the right. */
    private fun stepHead(step: String, iconRes: Int, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@MainActivity).apply {
                    text = step
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(C_ACCENT))
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(26), dp(26)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(Color.parseColor(C_INK))
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                },
            )
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
        }

    /** Small icon + label above a control. */
    private fun optionLabel(iconRes: Int, label: String): LinearLayout =
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
                    textSize = 14f
                    setTextColor(Color.parseColor(C_MUTED))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }

    /** Equal-width selectable chips; the selected one fills green. */
    private fun chipGroup(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val chips = mutableListOf<TextView>()
        fun paint(index: Int) {
            chips.forEachIndexed { i, chip ->
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(if (i == index) C_ACCENT else C_SURFACE))
                }
                chip.setTextColor(Color.parseColor(if (i == index) "#FFFFFF" else C_INK))
            }
        }
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    paint(i)
                    onSelect(i)
                }
            }
            chips.add(chip)
            group.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (i > 0) marginStart = dp(8)
                },
            )
        }
        paint(selected)
        return group
    }

    /** Icon + label with a platform Switch on the right. */
    private fun switchRow(
        iconRes: Int,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout =
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
            addView(
                Switch(this@MainActivity).apply {
                    isChecked = checked
                    contentDescription = label
                    setOnCheckedChangeListener { _, value -> onChange(value) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
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
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (error) C_ERROR else C_ACCENT))
        }
    }

    /**
     * Adds a step card and, unless it is the last one, a short timeline line under its number so
     * the three steps read as a sequence instead of three disconnected boxes.
     */
    private fun addStep(root: LinearLayout, card: View, last: Boolean) {
        root.addView(
            card,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (last) {
            root.addView(View(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))
        } else {
            val line = View(this).apply { setBackgroundColor(Color.parseColor(C_BORDER)) }
            root.addView(line, LinearLayout.LayoutParams(dp(2), dp(14)).apply { marginStart = dp(50) })
        }
    }

    private fun cardGap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** One primary button that changes with state: green Start with a play glyph, outlined Stop. */
    private fun setMirroring(on: Boolean) {
        mirroring = on
        if (!::primaryButton.isInitialized) return
        primaryButton.text = if (on) "Stop mirroring" else "Start mirroring"
        paintButton(primaryButton, !on, if (on) R.drawable.ic_stop else R.drawable.ic_play)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        // Header: brand mark on a soft squircle, title and one-line promise.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_logo)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor(C_SURFACE))
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
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
            addView(stepHead("1", R.drawable.ic_laptop, "Find your laptop"))
            laptopStatus = TextView(this@MainActivity).apply {
                text = "Not found yet. Start the receiver on your laptop first."
                textSize = 14f
                setTextColor(Color.parseColor(C_MUTED))
                setPadding(0, dp(10), 0, dp(12))
            }
            addView(laptopStatus)
            val find = Button(this@MainActivity).apply {
                text = "Find laptop"
                setOnClickListener { discoverReceiver() }
            }
            paintButton(find, true, R.drawable.ic_search)
            addView(find, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addStep(root, laptopCard, last = false)

        // Step 2: pairing code
        val codeCard = card().apply {
            addView(stepHead("2", R.drawable.ic_key, "Enter the pairing code"))
            codeField = EditText(this@MainActivity).apply {
                hint = "e.g. H5T2"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(4))
                setSingleLine()
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.3f
                gravity = Gravity.CENTER
                textSize = 26f
                setTextColor(Color.parseColor(C_INK))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor(C_SURFACE))
                    setStroke(dp(1), Color.parseColor(C_BORDER))
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            addView(
                codeField,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Four characters, shown on your laptop."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
        addStep(root, codeCard, last = false)

        // Step 3: options — chips instead of dropdowns, a switch for audio.
        val optionsCard = card().apply {
            addView(stepHead("3", R.drawable.ic_settings, "Options"))

            addView(optionLabel(R.drawable.ic_gauge, "Quality"), cardGap())
            addView(
                chipGroup(listOf("1080p · 16 Mbps", "720p · 6 Mbps"), qualityIndex) {
                    qualityIndex = it
                },
                cardGap().apply { topMargin = dp(6) },
            )

            addView(optionLabel(R.drawable.ic_apps, "Share"), cardGap().apply { topMargin = dp(16) })
            val shareLabels = if (Build.VERSION.SDK_INT >= 34) {
                listOf("Whole screen", "One app")
            } else {
                listOf("Whole screen")
            }
            addView(
                chipGroup(shareLabels, shareIndex) { shareIndex = it },
                cardGap().apply { topMargin = dp(6) },
            )

            addView(
                switchRow(R.drawable.ic_volume, "Internal audio", audioOn) { audioOn = it },
                cardGap().apply { topMargin = dp(16) },
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = "1080p is best effort. If the phone gets hot, use 720p."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    setPadding(0, dp(14), 0, 0)
                },
            )
        }
        addStep(root, optionsCard, last = true)

        // One primary action that changes with state: Start -> Stop.
        primaryButton = Button(this).apply {
            text = "Start mirroring"
            setOnClickListener {
                if (mirroring) {
                    val intent = Intent(this@MainActivity, MirrorService::class.java)
                        .setAction(MirrorService.ACTION_STOP)
                    startService(intent)
                    setMirroring(false)
                    setStatus("Stopped.")
                } else {
                    requestCapture()
                }
            }
        }
        paintButton(primaryButton, true, R.drawable.ic_play)
        root.addView(primaryButton, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // Status line: a state dot plus one clear next step.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            statusDot = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(C_ACCENT))
                }
            }
            addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
            statusLabel = TextView(this@MainActivity).apply {
                text = "Start the receiver on your laptop, then find it here."
                textSize = 14f
                setTextColor(Color.parseColor(C_MUTED))
            }
            addView(
                statusLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }
        root.addView(
            statusRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

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
        val quality = qualityIndex
        val audio = audioOn
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
        val shareApp = Build.VERSION.SDK_INT >= 34 && shareIndex == 1
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
        setMirroring(true)
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