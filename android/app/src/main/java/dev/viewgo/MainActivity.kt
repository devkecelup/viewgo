package dev.viewgo

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
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
import java.net.Inet4Address

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
    private lateinit var hostField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var laptopStatus: TextView
    private lateinit var statusDot: View
    private lateinit var primaryButton: Button
    private var mirroring = false

    // Option state — chip rows and a switch replace the old spinners.
    private var qualityIndex = 0
    private var fpsIndex = 0
    private var shareIndex = 0
    private var audioOn = true

    private var pending: Pending? = null

    /** Service -> UI state: the activity is often in the background while pairing happens. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val message = intent.getStringExtra(MirrorService.EXTRA_MESSAGE) ?: return
            val error = intent.getBooleanExtra(MirrorService.EXTRA_ERROR, false)
            when (intent.getStringExtra(MirrorService.EXTRA_STATE)) {
                MirrorService.STATE_LIVE -> {
                    setStatus(message)
                    setMirroring(true)
                }
                MirrorService.STATE_STOPPED -> {
                    setStatus(message, error)
                    setMirroring(false)
                }
                else -> setStatus(message, error)
            }
        }
    }

    private data class Pending(
        val host: String,
        val port: Int,
        val code: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val fps: Int,
        val audio: Boolean,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun shape(radiusDp: Int, fillColor: String, strokeColor: String? = null, strokeWidthDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillColor))
            if (strokeColor != null) setStroke(dp(strokeWidthDp), Color.parseColor(strokeColor))
        }

    private fun ripple(content: GradientDrawable, rippleColor: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = content.cornerRadius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(16, C_BG, C_BORDER, 1)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    /** Section head: the section glyph plus its title. */
    private fun sectionHead(iconRes: Int, title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(Color.parseColor(C_INK))
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                },
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

    /** Equal-width selectable chips; the selected one fills green, the others use a green outline. */
    private fun chipGroup(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val chips = mutableListOf<TextView>()
        fun paint(index: Int) {
            chips.forEachIndexed { i, chip ->
                val isSelected = i == index
                val bg = shape(12, if (isSelected) C_ACCENT else C_BG, C_ACCENT, if (isSelected) 0 else 1)
                val rippleColor = if (isSelected) Color.parseColor("#40FFFFFF") else Color.parseColor("#1A0F7B4F")
                chip.background = ripple(bg, rippleColor)
                chip.setTextColor(Color.parseColor(if (isSelected) "#FFFFFF" else C_ACCENT))
            }
        }
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    paint(i)
                    onSelect(i)
                }
            }
            chips.add(chip)
            group.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (i > 0) marginStart = dp(8)
                },
            )
        }
        paint(selected)
        return group
    }

    /** Icon + label with a tinted platform Switch on the right. */
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
            val accent = Color.parseColor(C_ACCENT)
            val trackStates = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#B2D8C8"), Color.parseColor(C_BORDER)),
            )
            val thumbStates = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.parseColor(C_SURFACE)),
            )
            addView(
                Switch(this@MainActivity).apply {
                    isChecked = checked
                    contentDescription = label
                    trackTintList = trackStates
                    thumbTintList = thumbStates
                    setOnCheckedChangeListener { _, value -> onChange(value) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun paintButton(button: Button, filled: Boolean, iconRes: Int, fill: String = C_ACCENT) {
        button.gravity = Gravity.CENTER
        button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        button.compoundDrawablePadding = dp(8)
        val bg = shape(14, if (filled) fill else C_BG, if (filled) fill else C_ACCENT, if (filled) 0 else 1)
        val rippleColor = if (filled) Color.parseColor("#40FFFFFF") else Color.parseColor("#1A0F7B4F")
        button.background = ripple(bg, rippleColor)
        button.setTextColor(if (filled) Color.WHITE else Color.parseColor(C_ACCENT))
        button.typeface = Typeface.DEFAULT_BOLD
        button.textSize = 16f
        button.isAllCaps = false
        button.setPadding(dp(20), dp(14), dp(20), dp(14))
        button.elevation = if (filled) dp(3).toFloat() else 0f
    }

    private fun codeBg(focused: Boolean): RippleDrawable {
        val bg = shape(14, C_SURFACE, if (focused) C_ACCENT else C_BORDER, if (focused) 2 else 1)
        return ripple(bg, Color.parseColor("#1A0F7B4F"))
    }

    private fun searchButton(): TextView = TextView(this).apply {
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        gravity = Gravity.CENTER
        background = ripple(shape(12, C_ACCENT), Color.parseColor("#40FFFFFF"))
        contentDescription = "Find laptop"
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { discoverReceiver() }
    }

    private fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.setTextColor(Color.parseColor(if (error) C_ERROR else C_MUTED))
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (error) C_ERROR else C_ACCENT))
        }
    }

    private fun addCard(root: LinearLayout, card: View) {
        root.addView(
            card,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            View(this),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)),
        )
    }

    private fun cardGap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** One primary button that changes with state: green Start with a play glyph, red Stop. */
    private fun setMirroring(on: Boolean) {
        mirroring = on
        if (!::primaryButton.isInitialized) return
        primaryButton.text = if (on) "Stop mirroring" else "Start mirroring"
        paintButton(
            primaryButton,
            true,
            if (on) R.drawable.ic_stop else R.drawable.ic_play,
            if (on) C_ERROR else C_ACCENT,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            setPadding(dp(20), 0, dp(20), dp(8))
        }

        // Header: brand mark on a soft squircle, title and one-line promise.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_logo)
                    background = shape(14, C_SURFACE)
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
        content.addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(View(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        // Connection card: find the laptop, then enter the code.
        val connectionCard = card().apply {
            addView(sectionHead(R.drawable.ic_laptop, "Connect to laptop"))

            val laptopRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_laptop)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)),
                )
                laptopStatus = TextView(this@MainActivity).apply {
                    text = "Tap search, or type the laptop's IP address below."
                    textSize = 15f
                    setTextColor(Color.parseColor(C_MUTED))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(
                    laptopStatus,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    },
                )
                addView(
                    searchButton(),
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) },
                )
            }
            addView(
                laptopRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )

            addView(optionLabel(R.drawable.ic_laptop, "Laptop IP (IPv4)"), cardGap().apply { topMargin = dp(18) })
            hostField = EditText(this@MainActivity).apply {
                hint = "192.168.1.24"
                // Digits plus the two separators a manual address needs; no alphabetic keyboard.
                keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.:")
                filters = arrayOf(InputFilter.LengthFilter(21))
                setSingleLine()
                typeface = Typeface.MONOSPACE
                textSize = 18f
                setTextColor(Color.parseColor(C_INK))
                setHintTextColor(Color.parseColor(C_MUTED))
                background = codeBg(false)
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    background = codeBg(hasFocus)
                }
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            addView(hostField, cardGap().apply { topMargin = dp(8) })
            addView(
                TextView(this@MainActivity).apply {
                    text = "Optional. Type the laptop's IPv4 address if the search shows an IPv6 one. Add :port only if your receiver uses another port."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    setPadding(0, dp(8), 0, 0)
                },
            )

            addView(optionLabel(R.drawable.ic_key, "Pairing code"), cardGap().apply { topMargin = dp(18) })
            codeField = EditText(this@MainActivity).apply {
                hint = "AB3D"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(4))
                setSingleLine()
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.28f
                gravity = Gravity.CENTER
                textSize = 26f
                setTextColor(Color.parseColor(C_INK))
                setHintTextColor(Color.parseColor(C_MUTED))
                background = codeBg(false)
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    background = codeBg(hasFocus)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        s?.let {
                            val upper = it.toString().uppercase()
                            if (upper != it.toString()) {
                                it.replace(0, it.length, upper)
                            }
                        }
                    }
                })
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            addView(codeField, cardGap().apply { topMargin = dp(8) })
            addView(
                TextView(this@MainActivity).apply {
                    text = "Type the 4-character code shown on your laptop."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
        addCard(content, connectionCard)

        // Flow picture and live status.
        content.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_flow)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120),
            ).apply { topMargin = dp(6) },
        )
        content.addView(
            TextView(this).apply {
                text = "Both devices must be on the same Wi-Fi network."
                textSize = 13f
                setTextColor(Color.parseColor(C_MUTED))
                gravity = Gravity.CENTER
            },
            cardGap(),
        )
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            statusDot = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(C_ACCENT))
                }
            }
            addView(
                statusDot,
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) },
            )
            statusLabel = TextView(this@MainActivity).apply {
                text = "Start the receiver on your laptop, then find it here."
                textSize = 14f
                setTextColor(Color.parseColor(C_MUTED))
            }
            addView(
                statusLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        content.addView(statusRow, cardGap())
        content.addView(View(this), cardGap().apply { height = dp(12) })

        // Options card: quality, frame rate, share target, audio.
        val optionsCard = card().apply {
            addView(sectionHead(R.drawable.ic_settings, "Options"))

            addView(optionLabel(R.drawable.ic_gauge, "Quality"), cardGap())
            addView(
                chipGroup(listOf("1080p", "720p"), qualityIndex) {
                    qualityIndex = it
                },
                cardGap().apply { topMargin = dp(6) },
            )

            addView(optionLabel(R.drawable.ic_flow, "Frame rate"), cardGap().apply { topMargin = dp(16) })
            addView(
                chipGroup(listOf("30 fps", "60 fps"), fpsIndex) { fpsIndex = it },
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
                    text = "1080p is best effort. 60 fps needs a stronger phone and more bitrate; " +
                        "if the phone gets hot, use 720p."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    setPadding(0, dp(14), 0, 0)
                },
            )
        }
        addCard(content, optionsCard)

        // One primary action pinned to the bottom: Start -> Stop.
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

        val scroll = ScrollView(this).apply { addView(content) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                primaryButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                    setMargins(dp(20), dp(12), dp(20), 0)
                },
            )
        }
        setContentView(outer)
        // Content must clear the status bar: with targetSdk 35 Android 15 draws edge-to-edge, so
        // without this the header sits under the battery/clock and Stop can hide behind the nav bar.
        outer.setOnApplyWindowInsetsListener { view, insets ->
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
            view.setPadding(0, dp(18) + top, 0, dp(16) + bottom)
            insets
        }
        outer.requestApplyInsets()

        hostField.setText(getPreferences(MODE_PRIVATE).getString(PREF_TARGET, ""))
        val stateFilter = IntentFilter(MirrorService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, stateFilter)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    private fun requestCapture() {
        val typed = hostField.text.toString()
        val target = parseTarget(typed)
        if (target == null) {
            setStatus("Enter the laptop's IPv4 address, like 192.168.1.24.", error = true)
            hostField.requestFocus()
            return
        }
        val code = normalizeCode(codeField.text.toString())
        if (code == null) {
            setStatus("The pairing code has 4 letters or digits.", error = true)
            return
        }
        val (host, port) = target
        getPreferences(MODE_PRIVATE).edit().putString(PREF_TARGET, typed.trim()).apply()
        val quality = qualityIndex
        val audio = audioOn
        val fps = if (fpsIndex == 1) 60 else 30
        pending = Pending(
            host = host,
            port = port,
            code = code,
            width = if (quality == 0) 1080 else 720,
            height = if (quality == 0) 1920 else 1280,
            // 60 fps needs roughly a third more bitrate to keep the same detail per frame.
            bitrate = when {
                quality == 0 && fps == 60 -> 24_000_000
                quality == 0 -> 16_000_000
                fps == 60 -> 10_000_000
                else -> 6_000_000
            },
            fps = fps,
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
            putExtra(MirrorService.EXTRA_FPS, job.fps)
            putExtra(MirrorService.EXTRA_AUDIO, job.audio)
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        setMirroring(true)
        setStatus("Connecting to ${job.host}…")
        AppLog.line("Capture approved, starting the service")
        moveTaskToBack(true)
    }

    private fun discoverReceiver() {
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
                    setStatus("Wi-Fi discovery failed. Type the laptop's IP address instead.", error = true)
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
                                setStatus("Could not resolve the laptop. Type its IP address instead.", error = true)
                            }
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            // mDNS advertises every address the laptop has, and Android can hand
                            // back the IPv6 one while the receiver listens on IPv4 only.
                            val address = preferredIpv4(resolved)
                            if (address == null) {
                                runOnUiThread {
                                    laptopStatus.setTextColor(Color.parseColor(C_ERROR))
                                    laptopStatus.text = "Found ${resolved.serviceName}, but only over IPv6."
                                    setStatus("Type the laptop's IPv4 address above.", error = true)
                                }
                                return
                            }
                            val target = if (resolved.port == 47810) address else "$address:${resolved.port}"
                            runOnUiThread {
                                hostField.setText(target)
                                laptopStatus.setTextColor(Color.parseColor(C_ACCENT))
                                laptopStatus.text = "Found ${resolved.serviceName} at $address. Enter the code."
                                setStatus("Laptop found. Enter the pairing code.")
                            }
                            AppLog.line("Local discovery found ${resolved.serviceName} at $target")
                        }
                    })
                } catch (error: UnsupportedOperationException) {
                    runOnUiThread {
                        laptopStatus.text = "This Android version cannot resolve local services."
                        setStatus("Discovery unavailable. Type the laptop's IP address instead.", error = true)
                    }
                }
            }
        })
    }

    private fun preferredIpv4(service: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            return service.hostAddresses
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        }
        @Suppress("DEPRECATION")
        val host = service.host ?: return null
        return if (host is Inet4Address) host.hostAddress else null
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val PREF_TARGET = "target"
    }

}