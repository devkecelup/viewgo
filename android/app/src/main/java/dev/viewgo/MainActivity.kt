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
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address

// Palette from the mobile redesign (code.html): slate ground, emerald action, radius 24.
private const val C_BG = "#FFFFFF"
private const val C_SURFACE = "#F8FAFC"
private const val C_BORDER = "#E2E8F0"
private const val C_INK = "#0F172A"
private const val C_MUTED = "#64748B"
private const val C_FAINT = "#94A3B8"
private const val C_ACCENT = "#047857"
private const val C_ACCENT_MID = "#059669"
private const val C_ACCENT_SOFT = "#ECFDF5"
private const val C_ACCENT_EDGE = "#A7F3D0"
private const val C_ERROR = "#B42318"

class MainActivity : Activity() {

    private lateinit var codeSlots: List<EditText>
    private lateinit var hostField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var laptopStatus: TextView
    private lateinit var statusDot: View
    private lateinit var primaryButton: Button
    private var mirroring = false

    // Search state: one discovery at a time, and a deadline so a missing laptop
    // falls back to the manual IP field instead of spinning forever.
    private var searching = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryListener: NsdManager.DiscoveryListener? = null

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
        background = shape(24, C_BG, C_BORDER, 1)
        elevation = dp(2).toFloat()
        setPadding(dp(20), dp(18), dp(20), dp(18))
    }

    /** Row head above a control: small glyph plus uppercase label and an optional right hint. */
    private fun optionLabel(iconRes: Int, label: String, hint: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.parseColor(C_ACCENT))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(16), dp(16)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = label.uppercase()
                    textSize = 12f
                    letterSpacing = 0.06f
                    setTextColor(Color.parseColor(C_MUTED))
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
            if (hint != null) {
                addView(
                    TextView(this@MainActivity).apply {
                        text = hint
                        textSize = 11f
                        setTextColor(Color.parseColor(C_FAINT))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

    /** Emphasis box around a glyph, as used by the card heads. */
    private fun iconBox(iconRes: Int, tint: String, fill: String): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor(tint))
            background = shape(12, fill)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Hairline separator inside a card. */
    private fun rule(): View = View(this).apply { background = shape(1, C_BORDER) }

    /** Segmented pill selector: a slate track holding equal cells; the selected cell fills
     *  emerald and shows its badge (HD / Fast / Smooth / Eco) on a tinted chip. */
    private fun segmented(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val cells = mutableListOf<LinearLayout>()
        fun paint(index: Int) {
            cells.forEachIndexed { i, cell ->
                val on = i == index
                cell.background = if (on) {
                    ripple(shape(12, C_ACCENT), Color.parseColor("#40FFFFFF"))
                } else {
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                }
                val title = cell.getChildAt(0) as TextView
                title.setTextColor(Color.parseColor(if (on) "#FFFFFF" else C_MUTED))
                if (cell.childCount > 1) {
                    val tag = cell.getChildAt(1) as TextView
                    tag.setTextColor(Color.parseColor(if (on) "#D1FAE5" else C_FAINT))
                    tag.background = if (on) shape(4, C_ACCENT_MID) else null
                }
            }
        }
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(16, "#F1F2F3")
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        items.forEachIndexed { i, (label, badge) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    paint(i)
                    onSelect(i)
                }
            }
            cell.addView(
                TextView(this).apply {
                    text = label
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (badge.isNotEmpty()) {
                cell.addView(
                    TextView(this).apply {
                        text = badge
                        textSize = 9f
                        typeface = Typeface.DEFAULT_BOLD
                        isAllCaps = false
                        letterSpacing = 0.04f
                        gravity = Gravity.CENTER
                        setPadding(dp(6), dp(1), dp(6), dp(1))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(6) },
                )
            }
            cells.add(cell)
            group.addView(
                cell,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (i > 0) marginStart = dp(4)
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
        button.compoundDrawablePadding = dp(10)
        val textColor = if (filled) Color.WHITE else Color.parseColor(C_ACCENT)
        // The redesign's CTA runs dark -> mid -> dark across its width; a plain fill elsewhere.
        val bg: GradientDrawable = if (filled) {
            GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(16).toFloat()
                colors =
                    if (fill == C_ACCENT) {
                        intArrayOf(
                            Color.parseColor(C_ACCENT),
                            Color.parseColor(C_ACCENT_MID),
                            Color.parseColor(C_ACCENT),
                        )
                    } else {
                        intArrayOf(Color.parseColor(fill), Color.parseColor(fill))
                    }
            }
        } else {
            shape(16, C_BG, C_ACCENT, 1)
        }
        val rippleColor =
            if (filled) Color.parseColor("#40FFFFFF") else Color.parseColor("#33047857")
        button.background = ripple(bg, rippleColor)
        button.setTextColor(textColor)
        button.compoundDrawableTintList = ColorStateList.valueOf(textColor)
        button.typeface = Typeface.DEFAULT_BOLD
        button.textSize = 16f
        button.isAllCaps = false
        button.setPadding(dp(20), dp(16), dp(20), dp(16))
        button.elevation = if (filled) dp(4).toFloat() else 0f
    }

    private fun codeBg(focused: Boolean): RippleDrawable {
        val bg = shape(16, C_SURFACE, if (focused) C_ACCENT_MID else C_BORDER, if (focused) 2 else 1)
        return ripple(bg, Color.parseColor("#33047857"))
    }

    /** Discovery deadline: stop the scan and point the user at the manual IP field. */
    private val searchTimeout = Runnable {
        val listener = discoveryListener ?: return@Runnable
        runCatching { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) }
        finishSearch()
        laptopStatus.setTextColor(Color.parseColor(C_MUTED))
        laptopStatus.text = "No laptop found on this Wi-Fi. Type its IP address below."
        setStatus("No laptop found. Enter the laptop's IP address manually.", error = true)
    }

    private fun finishSearch() {
        searching = false
        discoveryListener = null
        mainHandler.removeCallbacks(searchTimeout)
    }

    /** The four PIN slots read back as one 4-character code. */
    private fun pairingCode(): String = codeSlots.joinToString("") { it.text.toString() }

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
            setBackgroundColor(Color.parseColor(C_SURFACE))
            setPadding(dp(20), 0, dp(20), dp(8))
        }

        // Header: emerald tile with the white screen glyph, title and one-line promise.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_mark)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        orientation = GradientDrawable.Orientation.BL_TR
                        colors = intArrayOf(
                            Color.parseColor(C_ACCENT),
                            Color.parseColor(C_ACCENT_MID),
                        )
                    }
                    setPadding(dp(12), dp(12), dp(12), dp(12))
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
                            textSize = 24f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor(C_INK))
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Mirror your phone to laptop over Wi-Fi"
                            textSize = 12f
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
        content.addView(View(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))

        // Connect card: auto-discovery banner, manual IP, and the 4-slot pairing code.
        val connect = card().apply {
            // Head: tinted glyph box, title and subtitle over a hairline.
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        iconBox(R.drawable.ic_laptop, C_ACCENT, C_ACCENT_SOFT),
                        LinearLayout.LayoutParams(dp(36), dp(36)),
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = "Connect to laptop"
                                    textSize = 16f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(Color.parseColor(C_INK))
                                },
                            )
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = "Search receiver or enter connection details"
                                    textSize = 11f
                                    setTextColor(Color.parseColor(C_FAINT))
                                },
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(10)
                        },
                    )
                },
            )
            addView(rule(), cardGap().apply { topMargin = dp(14) })

            // Primary action: one tap scans this Wi-Fi for the receiver.
            val banner = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        orientation = GradientDrawable.Orientation.LEFT_RIGHT
                        colors = intArrayOf(
                            Color.parseColor(C_ACCENT_SOFT),
                            Color.parseColor("#DDF7EA"),
                        )
                        setStroke(dp(1), Color.parseColor(C_ACCENT_EDGE))
                    },
                    Color.parseColor("#33059669"),
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "Search this Wi-Fi for the laptop automatically"
                setOnClickListener { discoverReceiver() }
                addView(
                    FrameLayout(this@MainActivity).apply {
                        clipChildren = false
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageResource(R.drawable.ic_search)
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(12).toFloat()
                                    setColor(Color.parseColor(C_ACCENT_MID))
                                }
                                setPadding(dp(11), dp(11), dp(11), dp(11))
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            },
                            FrameLayout.LayoutParams(dp(40), dp(40)),
                        )
                        // Scanning hint dot, hanging over the tile corner as in the redesign.
                        addView(
                            View(this@MainActivity).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.parseColor("#10B981"))
                                    setStroke(dp(2), Color.WHITE)
                                }
                            },
                            FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                                gravity = Gravity.TOP or Gravity.END
                                setMargins(0, dp(-3), dp(-3), 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(dp(40), dp(40)),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Cari Laptop Otomatis"
                                        textSize = 12f
                                        typeface = Typeface.DEFAULT_BOLD
                                        setTextColor(Color.parseColor(C_INK))
                                    },
                                )
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "RECOMMENDED"
                                        textSize = 9f
                                        typeface = Typeface.DEFAULT_BOLD
                                        letterSpacing = 0.06f
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor(C_ACCENT))
                                        background = shape(4, C_BG, C_ACCENT_EDGE, 1)
                                        setPadding(dp(6), dp(2), dp(6), dp(2))
                                    },
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply { marginStart = dp(6) },
                                )
                            },
                        )
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Quick Auto-Discovery: tap to scan Wi-Fi"
                                textSize = 11f
                                setTextColor(Color.parseColor("#475569"))
                                setPadding(0, dp(2), 0, 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12)
                    },
                )
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_chevron)
                        background = shape(16, C_BG, C_ACCENT_EDGE, 1)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(10) },
                )
            }
            addView(banner, cardGap().apply { topMargin = dp(16) })

            // Manual path behind a centred label, as the redesign splits it.
            val manualDivider = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    View(this@MainActivity).apply { background = shape(1, C_BORDER) },
                    LinearLayout.LayoutParams(0, dp(1), 1f),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "ATAU MANUAL"
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.08f
                        setTextColor(Color.parseColor(C_FAINT))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                addView(
                    View(this@MainActivity).apply { background = shape(1, C_BORDER) },
                    LinearLayout.LayoutParams(0, dp(1), 1f),
                )
            }
            addView(manualDivider, cardGap().apply { topMargin = dp(18) })

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "LAPTOP IP (OPTIONAL / MANUAL)"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.06f
                            setTextColor(Color.parseColor(C_MUTED))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Port :47810"
                            textSize = 11f
                            setTextColor(Color.parseColor(C_FAINT))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                cardGap().apply { topMargin = dp(18) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Ketik IP jika auto-search tidak menemukan receiver"
                    textSize = 11f
                    setTextColor(Color.parseColor(C_MUTED))
                },
                cardGap().apply { topMargin = dp(4) },
            )
            hostField = EditText(this@MainActivity).apply {
                hint = "e.g. 192.168.1.X"
                keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.:")
                filters = arrayOf(InputFilter.LengthFilter(21))
                setSingleLine()
                typeface = Typeface.MONOSPACE
                textSize = 15f
                setTextColor(Color.parseColor(C_INK))
                setHintTextColor(Color.parseColor(C_FAINT))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_globe, 0, 0, 0)
                compoundDrawablePadding = dp(8)
                background = codeBg(false)
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    background = codeBg(hasFocus)
                }
                minHeight = dp(52)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        hostField,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        Button(this@MainActivity).apply {
                            contentDescription = "Search this Wi-Fi for the laptop"
                            minWidth = 0
                            minHeight = 0
                            elevation = 0f
                            isAllCaps = false
                            setPadding(0, 0, 0, 0)
                            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
                            compoundDrawableTintList =
                                ColorStateList.valueOf(Color.parseColor("#475569"))
                            background = ripple(
                                shape(16, "#F1F2F3", C_BORDER, 1),
                                Color.parseColor("#33047857"),
                            )
                            setOnClickListener { discoverReceiver() }
                        },
                        LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
                    )
                },
                cardGap().apply { topMargin = dp(8) },
            )

            laptopStatus = TextView(this@MainActivity).apply {
                text = "Auto-search scans this Wi-Fi; type the IP above if it fails."
                textSize = 12f
                setTextColor(Color.parseColor(C_MUTED))
                setPadding(0, dp(10), 0, 0)
            }
            addView(laptopStatus, cardGap())

            // Pairing code: four discrete slots that read back as one code.
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "PAIRING CODE"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.06f
                            setTextColor(Color.parseColor(C_MUTED))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Encrypted PIN"
                            textSize = 11f
                            setTextColor(Color.parseColor(C_ACCENT_MID))
                            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_key, 0, 0, 0)
                            compoundDrawablePadding = dp(4)
                            compoundDrawableTintList =
                                ColorStateList.valueOf(Color.parseColor(C_ACCENT_MID))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                cardGap().apply { topMargin = dp(18) },
            )
            val slotHints = listOf("A", "B", "3", "D")
            val pinRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            codeSlots = (0..3).map { index ->
                EditText(this@MainActivity).apply {
                    hint = slotHints[index]
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    filters = arrayOf(InputFilter.LengthFilter(1))
                    setSingleLine()
                    typeface = Typeface.MONOSPACE
                    letterSpacing = 0.1f
                    gravity = Gravity.CENTER
                    textSize = 20f
                    setTextColor(Color.parseColor(C_INK))
                    setHintTextColor(Color.parseColor(C_FAINT))
                    background = codeBg(false)
                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        background = codeBg(hasFocus)
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            if (s == null) return
                            val text = s.toString()
                            val upper = text.uppercase()
                            if (upper != text) {
                                s.replace(0, s.length, upper)
                                return
                            }
                            // Fill advances, deleting at the start of a slot walks back.
                            if (text.length == 1 && index < 3) {
                                codeSlots[index + 1].requestFocus()
                            } else if (text.isEmpty() && index > 0) {
                                val previous = codeSlots[index - 1]
                                previous.requestFocus()
                                previous.setSelection(previous.text.length)
                            }
                        }
                    })
                    minHeight = dp(52)
                    setPadding(0, dp(14), 0, dp(14))
                }.also { slot ->
                    pinRow.addView(
                        slot,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) marginStart = dp(8)
                        },
                    )
                }
            }
            addView(pinRow, cardGap().apply { topMargin = dp(8) })
            addView(
                TextView(this@MainActivity).apply {
                    text = "Type the 4-character code shown on your laptop screen"
                    textSize = 11f
                    setTextColor(Color.parseColor(C_FAINT))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
        addCard(content, connect)

        // Guide card: the redesign's phone -> laptop illustration over the live status line.
        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.parseColor(C_ACCENT_SOFT), Color.parseColor(C_BG))
                setStroke(dp(1), Color.parseColor("#D1FAE5"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        // Phone column: outlined body with a tinted screen and a home dot, label beneath.
        val phoneColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(12, C_BG, C_ACCENT, 2)
                    setPadding(dp(5), dp(6), dp(5), dp(6))
                    addView(
                        View(this@MainActivity).apply { background = shape(1, C_BORDER) },
                        LinearLayout.LayoutParams(dp(14), dp(2)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        },
                    )
                    addView(
                        View(this@MainActivity).apply { background = shape(4, C_ACCENT_SOFT) },
                        LinearLayout.LayoutParams(0, 0, 1f).apply {
                            setMargins(0, dp(4), 0, dp(4))
                        },
                    )
                    addView(
                        View(this@MainActivity).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor(C_ACCENT_MID))
                            }
                        },
                        LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        },
                    )
                },
                LinearLayout.LayoutParams(dp(48), dp(80)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Phone"
                    textSize = 10f
                    setTextColor(Color.parseColor(C_MUTED))
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(4)
                },
            )
        }
        // Beam column: three dots, the flash glyph, and the Wi-Fi Direct chip.
        val beamColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    listOf(C_ACCENT_MID, C_ACCENT_EDGE, C_ACCENT_MID).forEachIndexed { i, color ->
                        addView(
                            View(this@MainActivity).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.parseColor(color))
                                }
                            },
                            LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                                if (i > 0) marginStart = dp(5)
                            },
                        )
                    }
                },
            )
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_bolt)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, dp(4), 0, dp(4))
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "WI-FI DIRECT"
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(C_ACCENT))
                    background = shape(4, "#D1FAE5")
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                },
            )
        }
        // Laptop column: outlined screen standing on a solid base.
        val laptopColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            dp(8).toFloat(), dp(8).toFloat(),
                            dp(8).toFloat(), dp(8).toFloat(),
                            0f, 0f, 0f, 0f,
                        )
                        setColor(Color.parseColor(C_BG))
                        setStroke(dp(2), Color.parseColor(C_ACCENT))
                    }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    addView(
                        View(this@MainActivity).apply { background = shape(4, "#F1F2F3", C_BORDER, 1) },
                        LinearLayout.LayoutParams(dp(54), dp(32)).apply {
                            gravity = Gravity.CENTER
                        },
                    )
                },
                LinearLayout.LayoutParams(dp(78), dp(54)),
            )
            addView(
                View(this@MainActivity).apply { background = shape(2, C_ACCENT) },
                LinearLayout.LayoutParams(dp(92), dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            addView(
                View(this@MainActivity).apply { background = shape(2, "#94A3B8") },
                LinearLayout.LayoutParams(dp(30), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Laptop"
                    textSize = 10f
                    setTextColor(Color.parseColor(C_MUTED))
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(4)
                },
            )
        }
        val illustrationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(phoneColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                beamColumn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                },
            )
            addView(laptopColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        guide.addView(illustrationRow, cardGap())
        guide.addView(
            TextView(this).apply {
                text = "Both devices must be on the same Wi-Fi network"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(C_INK))
                gravity = Gravity.CENTER
            },
            cardGap().apply { topMargin = dp(10) },
        )
        guide.addView(
            TextView(this).apply {
                text = "Start the receiver on your laptop, then find it here"
                textSize = 11f
                setTextColor(Color.parseColor(C_MUTED))
                gravity = Gravity.CENTER
            },
            cardGap().apply { topMargin = dp(2) },
        )
        // Live status from the service: dot plus message, kept from the old status banner.
        statusDot = View(this@MainActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(C_ACCENT))
            }
        }
        statusLabel = TextView(this@MainActivity).apply {
            text = "Ready to mirror."
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.parseColor(C_MUTED))
        }
        guide.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    statusDot,
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) },
                )
                addView(
                    statusLabel,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
            cardGap().apply { topMargin = dp(10) },
        )
        addCard(content, guide)

        // Options card: segmented selectors under uppercase row heads, as in the redesign.
        val optionsCard = card().apply {
            // The head doubles as the quality row: gear + OPTIONS left, "Quality Mode" right.
            addView(optionLabel(R.drawable.ic_settings, "Options", hint = "Quality Mode"))
            addView(
                segmented(listOf("1080p" to "HD", "720p" to "Fast"), qualityIndex) {
                    qualityIndex = it
                },
                cardGap().apply { topMargin = dp(10) },
            )

            addView(rule(), cardGap().apply { topMargin = dp(16) })
            addView(
                optionLabel(R.drawable.ic_bolt, "Frame rate", hint = "Refresh Rate"),
                cardGap().apply { topMargin = dp(16) },
            )
            addView(
                segmented(listOf("30 FPS" to "Eco", "60 FPS" to "Smooth"), fpsIndex) { fpsIndex = it },
                cardGap().apply { topMargin = dp(10) },
            )

            addView(rule(), cardGap().apply { topMargin = dp(16) })
            addView(
                optionLabel(R.drawable.ic_apps, "Share", hint = "What to mirror"),
                cardGap().apply { topMargin = dp(16) },
            )
            val shareLabels = if (Build.VERSION.SDK_INT >= 34) {
                listOf("Whole screen", "One app")
            } else {
                listOf("Whole screen")
            }
            addView(
                segmented(shareLabels.map { it to "" }, shareIndex) { shareIndex = it },
                cardGap().apply { topMargin = dp(10) },
            )

            addView(rule(), cardGap().apply { topMargin = dp(16) })
            addView(
                switchRow(R.drawable.ic_volume, "Internal audio", audioOn) { audioOn = it },
                cardGap().apply { topMargin = dp(16) },
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = "1080p and 60 fps are best effort. If the phone gets hot, switch to 720p or 30 fps."
                    textSize = 12f
                    setTextColor(Color.parseColor(C_MUTED))
                    gravity = Gravity.CENTER
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
            // Slate ground under the white cards and the CTA dock, per the redesign.
            setBackgroundColor(Color.parseColor(C_SURFACE))
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
        mainHandler.removeCallbacks(searchTimeout)
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
        val code = normalizeCode(pairingCode())
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
        if (searching) return
        searching = true
        laptopStatus.setTextColor(Color.parseColor(C_MUTED))
        laptopStatus.text = "Searching your Wi-Fi for the laptop..."
        setStatus("Searching local Wi-Fi...")
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                manager.stopServiceDiscovery(this)
                runOnUiThread {
                    finishSearch()
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
                                finishSearch()
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
                                    finishSearch()
                                    laptopStatus.setTextColor(Color.parseColor(C_ERROR))
                                    laptopStatus.text = "Found ${resolved.serviceName}, but only over IPv6."
                                    setStatus("Type the laptop's IPv4 address above.", error = true)
                                }
                                return
                            }
                            val target = if (resolved.port == 47810) address else "$address:${resolved.port}"
                            runOnUiThread {
                                finishSearch()
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
                        finishSearch()
                        laptopStatus.text = "This Android version cannot resolve local services."
                        setStatus("Discovery unavailable. Type the laptop's IP address instead.", error = true)
                    }
                }
            }
        }
        discoveryListener = listener
        mainHandler.postDelayed(searchTimeout, SEARCH_TIMEOUT_MS)
        manager.discoverServices("_viewgo._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
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
        private const val SEARCH_TIMEOUT_MS = 8_000L
    }

}