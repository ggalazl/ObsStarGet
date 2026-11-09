package cl.astro.observability

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import cl.astro.observability.astro.AirmassModel
import cl.astro.observability.astro.DarkLimit
import cl.astro.observability.astro.NightResult
import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.SeasonPlanner
import cl.astro.observability.astro.SeasonResult
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.astro.WindowResult
import cl.astro.observability.chart.ChartPalette
import cl.astro.observability.chart.ChartText
import cl.astro.observability.chart.YAxisMode
import cl.astro.observability.data.Catalog
import cl.astro.observability.data.ResolvedCache
import cl.astro.observability.data.SiteList
import cl.astro.observability.data.TargetResolver
import cl.astro.observability.report.Summaries
import cl.astro.observability.report.VisibilityTable
import cl.astro.observability.ui.CatalogAdapter
import cl.astro.observability.ui.NightChartView
import cl.astro.observability.ui.SeasonChartView
import cl.astro.observability.ui.SkyChartView
import cl.astro.observability.ui.WindowChartView
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    /** Name look-ups run here, so that a slow network never holds up the diagrams (and vice versa). */
    private val netWorker = Executors.newSingleThreadExecutor()
    private val catalogReady = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val lookupGen = AtomicInteger(0)
    private val seasonGen = AtomicInteger(0)
    private lateinit var resolvedCache: ResolvedCache
    private lateinit var prefs: SharedPreferences
    private var red = false
    private lateinit var pal: ChartPalette
    private var cardColor = 0
    private val ui: UiColors get() = if (red) UI_RED else UI_DARK

    @Volatile
    private var catalog: Catalog? = null
    private var sites: List<Site> = emptyList()

    /** The targets being compared (at most [MAX_TARGETS]); target i keeps colour i. */
    private val targets = ArrayList<Target>()
    private var active = 0
    private var startDate: LocalDate = LocalDate.now()
    private var uiReady = false
    private var firstLaunch = false

    // Last computation
    private var calcs: List<ObservabilityCalculator> = emptyList()
    private var calcZone: ZoneId = ZoneId.of("UTC")
    private var windowResult: WindowResult? = null
    private var nightResults: List<NightResult> = emptyList()
    private var selectedNight = 0
    private var calcStart: LocalDate = LocalDate.now()
    private var calcNights = 1

    // Best time of year (active target)
    private var seasonResult: SeasonResult? = null
    private var seasonKey = ""
    private var seasonZone: ZoneId = ZoneId.of("UTC")
    private var seasonPicked: Int? = null

    /** Inputs of the year scan now running ("" if none). */
    private var seasonRunningKey = ""

    /** "Compute Best Observability Window" pressed: apply the best window when the scan finishes. */
    private var pendingBestWindow = false

    /** Scroll up to the diagrams once the next computation has been shown. */
    private var scrollToResults = false

    /** The chart open full screen, if any. */
    private var full: FullScreen? = null

    // Views
    private lateinit var targetInput: AutoCompleteTextView
    private lateinit var targetList: LinearLayout
    private lateinit var targetInfo: TextView
    private lateinit var siteSpinner: Spinner
    private lateinit var customBox: LinearLayout
    private lateinit var latEdit: EditText
    private lateinit var lonEdit: EditText
    private lateinit var heightEdit: EditText
    private lateinit var zoneEdit: AutoCompleteTextView
    private lateinit var siteInfo: TextView
    private lateinit var dateButton: Button
    private lateinit var nightsEdit: EditText
    private lateinit var dateInfo: TextView
    private lateinit var limitSpinner: Spinner
    private lateinit var darkSpinner: Spinner
    private lateinit var rangeSpinner: Spinner
    private lateinit var yModeSpinner: Spinner
    private lateinit var axisSpinner: Spinner
    private lateinit var axisNote: TextView
    private lateinit var scroll: ScrollView
    private lateinit var timeSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var windowCard: LinearLayout
    private lateinit var windowTitle: TextView
    private lateinit var windowView: WindowChartView
    private lateinit var windowSummary: TextView
    private lateinit var navLabel: TextView
    private lateinit var nightView: NightChartView
    private lateinit var skyView: SkyChartView
    private lateinit var table: TableLayout
    private lateinit var nightSummary: TextView
    private lateinit var seasonCard: LinearLayout
    private lateinit var seasonLine: View
    private lateinit var windowLine: View
    private lateinit var seasonTitle: TextView
    private lateinit var seasonView: SeasonChartView
    private lateinit var seasonStatus: TextView
    private lateinit var seasonSummary: TextView
    private lateinit var goNightButton: Button

    companion object {
        private val LIMITS = listOf(1.2, 1.3, 1.5, 1.75, 2.0, 2.5, 3.0)

        /** Bottom of the airmass axis of the night diagram and dark end of the date-window colours. */
        private val AXIS_MAX = listOf(1.5, 2.0, 2.5, 3.0)

        /**
         * Time scale of the diagrams: the site's own zone (with daylight saving), UT, the phone's zone, or a
         * fixed offset from UTC (codes "site", "UT", "phone", "+05:30"…).
         */
        private val UTC_OFFSET_MINUTES: List<Int> =
            ((-12..14).filter { it != 0 }.map { it * 60 } + listOf(-570, -210, 210, 270, 330, 345, 390, 570, 630, 765)).sorted()
        private val TIME_CODES: List<String> =
            listOf("site", "UT", "phone") + UTC_OFFSET_MINUTES.map { ZoneOffset.ofTotalSeconds(it * 60).id }
        private const val DEFAULT_AXIS_MAX = 2.5
        private val NIGHT_PRESETS = listOf(1, 7, 14, 30, 90, 180, 365)
        private const val MAX_NIGHTS = 366
        private const val MAX_TARGETS = 8
        private const val REQ_LOCATION = 7
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)

        const val APP_NAME = "ObsStarGet"
        const val APP_TITLE = "Astronomical object Observability"
        const val AUTHOR = "Gaspar Galaz"

        /** The project page on GitHub: new versions (Releases) and feedback (Issues). */
        const val GITHUB_USER = "ggalazl"
        const val PROJECT_URL = "https://github.com/$GITHUB_USER/ObsStarGet"

        /** Full-screen controls: intense bright green (bright red in the night-vision theme). */
        private const val FULL_GREEN = 0xFF00E676.toInt()
        private const val FULL_GREEN_INK = 0xFF00210E.toInt()
        private const val FULL_RED = 0xFFFF4A3D.toInt()
        private const val FULL_RED_INK = 0xFF140000.toInt()

        /**
         * Colours of the page: the wordmark, the section lines and the two Compute buttons. Each section has
         * its own colour, repeated in its line, its card titles and the button that computes it (blue = the
         * night diagrams, amber = the best window, teal = the date window). Night vision uses reds only.
         */
        private class UiColors(
            val sky: Int, val gold: Int, val violet: Int, val blue: Int, val amber: Int, val teal: Int, val gray: Int,
            val computeFill: Int, val computeInk: Int, val bestFill: Int, val bestInk: Int,
        )

        private val UI_DARK = UiColors(
            sky = 0xFF7DB8FF.toInt(), gold = 0xFFFFC94D.toInt(), violet = 0xFFA99BFF.toInt(), blue = 0xFF4D9BFF.toInt(),
            amber = 0xFFFFB300.toInt(), teal = 0xFF2EC4B6.toInt(), gray = 0xFF8F8E86.toInt(),
            computeFill = 0xFF1E6FE8.toInt(), computeInk = 0xFFFFFFFF.toInt(),
            bestFill = 0xFFFFB300.toInt(), bestInk = 0xFF231600.toInt(),
        )
        private val UI_RED = UiColors(
            sky = 0xFFD04038.toInt(), gold = 0xFFFF5A50.toInt(), violet = 0xFFB8322A.toInt(), blue = 0xFFFF5A50.toInt(),
            amber = 0xFFFF8A70.toInt(), teal = 0xFFC84A40.toInt(), gray = 0xFF8C2A25.toInt(),
            computeFill = 0xFFB3261E.toInt(), computeInk = 0xFFFFD0CA.toInt(),
            bestFill = 0xFF7A100A.toInt(), bestInk = 0xFFFFB0A0.toInt(),
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("observability", Context.MODE_PRIVATE)
        red = prefs.getBoolean("red", false)
        setTheme(if (red) R.style.AppTheme_Red else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        pal = if (red) ChartPalette.RED else ChartPalette.DARK
        cardColor = if (red) 0xFF1E0302.toInt() else 0xFF242423.toInt()
        sites = assets.open("sites.tsv").bufferedReader().use { SiteList.parse(it) }
        resolvedCache = ResolvedCache.parse(prefs.getString("resolved", null))
        buildUi()
        restoreState()
        uiReady = true
        // The catalogue loads on the worker thread; tasks queued later on the same thread see it.
        worker.execute {
            val c = assets.open("objects.tsv").bufferedReader().use { Catalog.parse(it) }
            catalog = c
            catalogReady.countDown()
            main.post {
                if (nightResults.isEmpty()) {
                    status.text = "Built-in catalogue: ${c.size} objects (NGC/IC/Messier, named stars). " +
                        "Other names are looked up online in NED and CDS Sesame (SIMBAD)."
                }
            }
        }
        if (targets.isNotEmpty() && (firstLaunch || prefs.getBoolean("has_results", false))) compute()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        seasonGen.incrementAndGet()
        full?.dialog?.dismiss()
        worker.shutdownNow()
        netWorker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: String = "", sizeSp: Float = 14f, color: Int = pal.textSecondary, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** A rounded card; its title and thin border take the colour of its section. */
    private fun card(parent: LinearLayout, title: String, accent: Int = pal.textMuted): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x55000000)
            }
        }
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        if (title.isNotEmpty()) c.addView(sectionTitle(title, accent))
        return c
    }

    private fun sectionTitle(title: String, color: Int = pal.textMuted) =
        text(title.uppercase(Locale.US), 12f, color, true).apply { letterSpacing = 0.08f }

    /** A coloured line that opens a section of the page: "LABEL ────────". */
    private fun sectionLine(parent: LinearLayout, label: String, color: Int): View {
        val r = row().apply { setPadding(dp(2), dp(18), dp(2), dp(2)) }
        r.addView(text(label.uppercase(Locale.US), 12f, color, true).apply {
            letterSpacing = 0.1f
            setPadding(0, 0, dp(10), 0)
        })
        r.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(1).toFloat()
            }
        }, LinearLayout.LayoutParams(0, dp(2), 1f))
        parent.addView(r)
        return r
    }

    /** "✦ ObsStarGet" with "Star" in gold. */
    private fun wordmark(): CharSequence {
        val sb = SpannableStringBuilder()
        fun part(t: String, c: Int) {
            val start = sb.length
            sb.append(t)
            sb.setSpan(ForegroundColorSpan(c), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        part("✦ ", ui.gold)
        part("Obs", ui.sky)
        part("Star", ui.gold)
        part("Get", ui.sky)
        return sb
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    private fun edit(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun spinner(items: List<String>, onChange: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (uiReady) onChange(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun labeled(parent: LinearLayout, label: String, view: View) {
        val r = row()
        r.addView(text(label, 14f, pal.textSecondary), LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(view, weighted())
        parent.addView(r)
    }

    /** A right-aligned "⤢ Full screen" control above a chart. */
    /** Colour of the full-screen controls and the ink on it. */
    private fun fullAccent() = if (red) FULL_RED else FULL_GREEN
    private fun fullInk() = if (red) FULL_RED_INK else FULL_GREEN_INK

    /** A right-aligned, filled "⤢ FULL SCREEN" button above a chart. */
    private fun expandRow(onClick: () -> Unit): View {
        val r = row().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        r.addView(text("⤢  FULL SCREEN", 15f, fullInk(), true).apply {
            setPadding(dp(16), dp(9), dp(16), dp(9))
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                setColor(fullAccent())
                cornerRadius = dp(20).toFloat()
            }
            contentDescription = "Show this diagram full screen"
            setOnClickListener { onClick() }
        })
        return r
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top bar: the app name in colour and the full title under it
        val bar = row().apply { setPadding(dp(16), dp(8), dp(4), dp(6)) }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("", 25f, pal.textPrimary, true).apply {
            text = wordmark()
            contentDescription = APP_NAME
        })
        names.addView(text(APP_TITLE, 15f, pal.textPrimary, true).apply { maxLines = 2 })
        bar.addView(names, weighted())
        bar.addView(text("⋮", 24f, pal.textPrimary).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            contentDescription = "Menu"
            setOnClickListener { showMenu(it) }
        })
        root.addView(bar)
        // A thin gold line under the header.
        root.addView(View(this).apply { setBackgroundColor((ui.gold and 0x00FFFFFF) or 0x66000000) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Author; instructions, disclaimer and feedback (also in the ⋮ menu)
        content.addView(text("by $AUTHOR", 13f, pal.textSecondary).apply { setPadding(dp(4), dp(8), 0, 0) })
        val ir = row()
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "Instructions" to { showInstructions() },
            "Disclaimer" to { showDisclaimer() },
            "Feedback" to { showFeedback() },
        )) {
            ir.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = label
                maxLines = 1
                setOnClickListener { action() }
            }, weighted())
        }
        content.addView(ir)

        // --- 1 · Targets and site
        sectionLine(content, "1 · Targets and site", ui.sky)
        val tc = card(content, "Targets (up to $MAX_TARGETS, compared on one diagram)", ui.sky)
        val tr = row()
        targetInput = AutoCompleteTextView(this).apply {
            hint = "M82, NGC 7531, Andromeda Galaxy, or RA Dec"
            isSingleLine = true
            threshold = 2
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                    resolveTarget()
                    true
                } else {
                    false
                }
            }
        }
        val adapter = CatalogAdapter(this, { catalog }, pal.textPrimary)
        targetInput.setAdapter(adapter)
        targetInput.setOnItemClickListener { _, _, pos, _ ->
            adapter.entryAt(pos)?.let { e ->
                hideKeyboard()
                addTarget(e.toTarget())
            }
        }
        tr.addView(targetInput, weighted())
        tr.addView(Button(this).apply {
            text = "Add"
            setOnClickListener { resolveTarget() }
        })
        tc.addView(tr)
        tc.addView(text("Names not in the built-in catalogue are looked up online in NED and SIMBAD (e.g. Cartwheel Galaxy, " +
            "Arp 220, UGC 10214). Coordinates: ICRS/J2000, e.g. 23:14:48.5 −43:35:60 or 348.702 −43.600 (degrees).",
            12f, pal.textMuted))
        targetList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        tc.addView(targetList)
        targetInfo = text("", 13f, pal.textSecondary).apply { setPadding(0, dp(6), 0, 0) }
        tc.addView(targetInfo)

        // --- Site
        val sc = card(content, "Observing site", ui.sky)
        siteSpinner = spinner(listOf("Custom location…") + sites.map { it.name }) {
            updateSiteUi()
            recomputeIfShown()
        }
        sc.addView(siteSpinner)
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val signedDecimal = InputType.TYPE_CLASS_TEXT
        latEdit = edit("Latitude ° (+N), e.g. -29.2567", signedDecimal)
        lonEdit = edit("Longitude ° (+E), e.g. -70.73", signedDecimal)
        heightEdit = edit("Height (m)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        zoneEdit = AutoCompleteTextView(this).apply {
            hint = "Time zone, e.g. America/Santiago"
            isSingleLine = true
            threshold = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line,
                ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") }.sorted() + "UTC"))
        }
        val r1 = row()
        r1.addView(latEdit, weighted())
        r1.addView(lonEdit, weighted())
        customBox.addView(r1)
        val r2 = row()
        r2.addView(heightEdit, weighted(0.6f))
        r2.addView(zoneEdit, weighted(1.4f))
        customBox.addView(r2)
        customBox.addView(Button(this).apply {
            text = "Use device location"
            setOnClickListener { requestLocation() }
        })
        sc.addView(customBox)
        siteInfo = text("", 13f, pal.textMuted).apply { setPadding(0, dp(6), 0, 0) }
        sc.addView(siteInfo)

        // --- Options
        sectionLine(content, "2 · Options", ui.violet)
        val oc = card(content, "", ui.violet)
        limitSpinner = spinner(LIMITS.map { "X ≤ " + ChartText.airmassLimit(it) }) {
            applyAxisMax()
            recomputeIfShown()
        }
        labeled(oc, "Airmass limit", limitSpinner)
        darkSpinner = spinner(DarkLimit.values().map { it.label }) { recomputeIfShown() }
        labeled(oc, "Dark time", darkSpinner)
        rangeSpinner = spinner(listOf("Sunset → sunrise (±1 h)", "Full day, noon → noon")) { recomputeIfShown() }
        labeled(oc, "Night range", rangeSpinner)
        yModeSpinner = spinner(listOf("Airmass", "Altitude")) {
            nightView.mode = currentYMode()
            savePrefs()
        }
        labeled(oc, "Y axis", yModeSpinner)
        axisSpinner = spinner(AXIS_MAX.map { "1.0 – " + ChartText.airmassLimit(it) }) {
            applyAxisMax()
            savePrefs()
        }
        labeled(oc, "Airmass axis", axisSpinner)
        axisNote = text("", 12f, pal.textMuted).apply { visibility = View.GONE }
        oc.addView(axisNote)
        timeSpinner = spinner(TIME_CODES.map { code ->
            when (code) {
                "site" -> "Local time of the site (with daylight saving)"
                "UT" -> "Universal time (UT)"
                "phone" -> "This phone's time zone (${ZoneId.systemDefault().id})"
                else -> ChartText.utcOffset(ZoneOffset.of(code).totalSeconds)
            }
        }) { recomputeIfShown() }
        labeled(oc, "Time axis", timeSpinner)
        modelSpinner = spinner(AirmassModel.values().map { it.label }) { recomputeIfShown() }
        labeled(oc, "Airmass model", modelSpinner)

        // --- 3 · Compute: the diagrams for the date window below, or first find the best window and use it
        sectionLine(content, "3 · Compute", ui.blue)
        content.addView(Button(this).apply {
            text = "Compute Observability"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.computeInk)
            backgroundTintList = ColorStateList.valueOf(ui.computeFill)
            minHeight = dp(60)
            setOnClickListener {
                hideKeyboard()
                compute()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        content.addView(Button(this).apply {
            text = "Compute Best Observability Window"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ui.bestInk)
            setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(ui.bestFill)
            setOnClickListener {
                hideKeyboard()
                computeBestWindow()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })
        content.addView(text("Best window: finds when the active target is best observed during the next 365 nights, " +
            "puts that period in the date window below and computes it.", 12f, pal.textMuted).apply {
            setPadding(dp(4), 0, dp(4), dp(2))
        })

        // --- 4 · Date window
        sectionLine(content, "4 · Date window", ui.teal)
        val dc = card(content, "", ui.teal)
        val dr = row()
        dateButton = Button(this).apply { setOnClickListener { pickDate() } }
        dr.addView(dateButton, weighted())
        dr.addView(Button(this).apply {
            text = "Tonight"
            setOnClickListener {
                startDate = currentNightDate(currentSite()?.zone ?: ZoneId.systemDefault())
                updateDateUi()
                recomputeIfShown()
            }
        })
        dc.addView(dr)
        val nr = row()
        nr.addView(text("Nights", 14f, pal.textSecondary), LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        nightsEdit = edit("1–366", InputType.TYPE_CLASS_NUMBER).apply {
            setOnEditorActionListener { _, _, _ ->
                updateDateUi()
                hideKeyboard()
                recomputeIfShown()
                true
            }
        }
        nr.addView(nightsEdit, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in NIGHT_PRESETS) {
            presets.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = n.toString()
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener {
                    nightsEdit.setText(n.toString())
                    updateDateUi()
                    recomputeIfShown()
                }
            })
        }
        nr.addView(HorizontalScrollView(this).apply { addView(presets) }, weighted())
        dc.addView(nr)
        dateInfo = text("", 13f, pal.textMuted)
        dc.addView(dateInfo)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        content.addView(progress)
        status = text("Loading catalogue…", 12f, pal.textMuted)
        content.addView(status)

        // --- Results: night first (the comparison), then the date window of the active target
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(results)

        sectionLine(results, "Night diagram · all targets", ui.blue)
        val ncard = card(results, "", ui.blue)
        val nav = row()
        nav.addView(Button(this).apply {
            text = "◀"
            contentDescription = "Previous night"
            setOnClickListener { selectNight(selectedNight - 1) }
        })
        navLabel = text("", 15f, pal.textPrimary, true).apply { gravity = Gravity.CENTER }
        nav.addView(navLabel, weighted())
        nav.addView(Button(this).apply {
            text = "▶"
            contentDescription = "Next night"
            setOnClickListener { selectNight(selectedNight + 1) }
        })
        ncard.addView(nav)
        ncard.addView(expandRow { openFull(FullKind.NIGHT) })
        nightView = NightChartView(this).apply {
            palette = pal
            onExpand = { openFull(FullKind.NIGHT) }
        }
        ncard.addView(nightView)
        ncard.addView(text("Touch and drag on the diagram: each target's airmass is written next to its point on the curve, " +
            "and the box gives the time, altitude, azimuth and Moon distance. The sky tracks below follow the cursor. " +
            "Double-tap a diagram (or ⤢) to see it full screen.", 12f, pal.textMuted))
        ncard.addView(sectionTitle("Visibility table (sorted by time of minimum airmass)").apply { setPadding(0, dp(12), 0, dp(4)) })
        table = TableLayout(this)
        ncard.addView(HorizontalScrollView(this).apply { addView(table) })
        skyView = SkyChartView(this).apply {
            palette = pal
            onExpand = { openFull(FullKind.SKY) }
        }
        nightView.onCursor = { skyView.cursor = it }
        ncard.addView(expandRow { openFull(FullKind.SKY) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        ncard.addView(skyView)
        nightSummary = text("", 14f, pal.textSecondary).apply {
            setPadding(0, dp(10), 0, 0)
            setTextIsSelectable(true)
        }
        ncard.addView(nightSummary)

        seasonLine = sectionLine(results, "Best time of year", ui.amber)
        seasonCard = card(results, "", ui.amber)
        seasonTitle = sectionTitle("", ui.amber)
        seasonCard.addView(seasonTitle)
        seasonCard.addView(expandRow { openFull(FullKind.SEASON) })
        seasonView = SeasonChartView(this).apply {
            palette = pal
            onNightSelected = { pickSeasonNight(it) }
            onExpand = { openFull(FullKind.SEASON) }
        }
        seasonCard.addView(seasonView)
        seasonStatus = text("", 12f, pal.textMuted)
        seasonCard.addView(seasonStatus)
        val sr = row()
        goNightButton = Button(this).apply {
            text = "Go to a night…"
            setOnClickListener { pickNightToShow() }
        }
        sr.addView(goNightButton, weighted())
        seasonCard.addView(sr)
        seasonSummary = text("", 13f, pal.textSecondary).apply {
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
        }
        seasonCard.addView(seasonSummary)

        windowLine = sectionLine(results, "Date-window diagram", ui.teal)
        windowCard = card(results, "", ui.teal)
        windowTitle = sectionTitle("", ui.teal)
        windowCard.addView(windowTitle)
        windowCard.addView(expandRow { openFull(FullKind.WINDOW) })
        windowView = WindowChartView(this).apply {
            palette = pal
            onNightSelected = { selectNight(it) }
            onExpand = { openFull(FullKind.WINDOW) }
        }
        windowCard.addView(windowView)
        windowSummary = text("", 13f, pal.textSecondary).apply { setPadding(0, dp(8), 0, 0) }
        windowCard.addView(windowSummary)

        sectionLine(results, "Save and share", ui.gray)
        val er = row()
        er.addView(Button(this).apply {
            text = "Save PNG"
            setOnClickListener { exportCharts(share = false) }
        }, weighted())
        er.addView(Button(this).apply {
            text = "Share"
            setOnClickListener { exportCharts(share = true) }
        }, weighted())
        results.addView(er)

        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            // Edge-to-edge is enforced from Android 15: keep content clear of the system bars and keyboard.
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
    }

    // ------------------------------------------------------------------ target list

    private fun refreshTargetList() {
        targetList.removeAllViews()
        for ((i, t) in targets.withIndex()) {
            val r = row().apply {
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { setActive(i) }
            }
            r.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(pal.seriesColor(i))
                    cornerRadius = dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(18), dp(5)).apply { rightMargin = dp(10) })
            val label = if (t.type.isNotEmpty()) "${t.name}  ·  ${t.type}" else t.name
            r.addView(text(label, 15f, if (i == active) pal.textPrimary else pal.textSecondary, i == active), weighted())
            r.addView(text("✕", 17f, pal.textMuted).apply {
                setPadding(dp(14), dp(2), dp(8), dp(2))
                contentDescription = "Remove ${t.name}"
                setOnClickListener { removeTarget(i) }
            })
            targetList.addView(r)
        }
        targetInfo.text = if (targets.isEmpty()) "Type a name or coordinates and press Add."
        else Summaries.describeTarget(targets[active]) + if (targets.size > 1) "\n(tap a target to make it the active one)" else ""
    }

    private fun addTarget(t: Target) {
        val existing = targets.indexOfFirst { it.name == t.name && Math.abs(it.raDeg - t.raDeg) < 1e-6 }
        if (existing >= 0) {
            setActive(existing)
            return
        }
        if (targets.size >= MAX_TARGETS) {
            toast("At most $MAX_TARGETS targets: remove one first.")
            return
        }
        targets.add(t)
        active = targets.size - 1
        targetInput.setText("", false)
        refreshTargetList()
        savePrefs()
        compute()
    }

    private fun removeTarget(i: Int) {
        if (i !in targets.indices) return
        targets.removeAt(i)
        active = active.coerceAtMost(targets.size - 1).coerceAtLeast(0)
        refreshTargetList()
        savePrefs()
        if (targets.isEmpty()) {
            generation.incrementAndGet()
            seasonGen.incrementAndGet()
            nightResults = emptyList()
            windowResult = null
            seasonResult = null
            seasonKey = ""
            results.visibility = View.GONE
            full?.dialog?.dismiss()
            savePrefs()
        } else {
            compute()
        }
    }

    private fun setActive(i: Int) {
        if (i !in targets.indices) return
        active = i
        refreshTargetList()
        savePrefs()
        if (nightResults.size == targets.size) {
            nightView.active = i
            skyView.active = i
            showNightDetails()
        }
        // The date-window diagram is per target.
        if (nightResults.isNotEmpty()) compute(keepNight = true)
    }

    // ------------------------------------------------------------------ state

    private fun restoreState() {
        val siteKey = prefs.getString("site", "lasilla")
        val idx = sites.indexOfFirst { it.key == siteKey }
        siteSpinner.setSelection(if (siteKey == "custom") 0 else if (idx >= 0) idx + 1 else 1)
        latEdit.setText(prefs.getString("lat", ""))
        lonEdit.setText(prefs.getString("lon", ""))
        heightEdit.setText(prefs.getString("height", ""))
        zoneEdit.setText(prefs.getString("zone", ZoneId.systemDefault().id), false)
        val saved = prefs.getString("start", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        startDate = saved ?: LocalDate.now(currentSite()?.zone ?: ZoneId.systemDefault())
        nightsEdit.setText(prefs.getInt("nights", 30).toString())
        limitSpinner.setSelection(LIMITS.indexOf(prefs.getFloat("limit", 2.0f).toDouble()).let { if (it < 0) 4 else it })
        darkSpinner.setSelection(prefs.getInt("dark", 0).coerceIn(0, DarkLimit.values().size - 1))
        rangeSpinner.setSelection(prefs.getInt("range", 0).coerceIn(0, 1))
        yModeSpinner.setSelection(prefs.getInt("ymode", 0).coerceIn(0, 1))
        axisSpinner.setSelection(AXIS_MAX.indexOf(prefs.getFloat("axismax", DEFAULT_AXIS_MAX.toFloat()).toDouble())
            .let { if (it < 0) AXIS_MAX.indexOf(DEFAULT_AXIS_MAX) else it })
        // Version 1.3 and earlier stored 0 = site, 1 = UT under "time".
        val timeCode = prefs.getString("timezone", null) ?: if (prefs.getInt("time", 0) == 1) "UT" else "site"
        timeSpinner.setSelection(TIME_CODES.indexOf(timeCode).coerceAtLeast(0))
        modelSpinner.setSelection(prefs.getInt("model", 0).coerceIn(0, AirmassModel.values().size - 1))
        nightView.mode = currentYMode()
        applyAxisMax()
        targets.clear()
        prefs.getString("targets", "")!!.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('\t')
            if (f.size >= 7) {
                val ra = f[1].toDoubleOrNull()
                val dec = f[2].toDoubleOrNull()
                if (ra != null && dec != null) {
                    targets.add(Target(f[0], ra, dec, f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f[5], f[6]))
                }
            }
        }
        if (!prefs.contains("targets")) {
            // First launch: start with an example so the first screen already shows the diagrams.
            targets.add(Target("NGC 7531", 348.702083, -43.599944, type = "Galaxy", source = "built-in (OpenNGC)"))
            firstLaunch = true
        }
        active = prefs.getInt("active", 0).coerceIn(0, (targets.size - 1).coerceAtLeast(0))
        refreshTargetList()
        updateSiteUi()
        updateDateUi()
    }

    private fun savePrefs() {
        val e = prefs.edit()
        val pos = siteSpinner.selectedItemPosition
        e.putString("site", if (pos <= 0) "custom" else sites[pos - 1].key)
        e.putString("lat", latEdit.text.toString())
        e.putString("lon", lonEdit.text.toString())
        e.putString("height", heightEdit.text.toString())
        e.putString("zone", zoneEdit.text.toString())
        e.putString("start", startDate.toString())
        e.putInt("nights", nights())
        e.putFloat("limit", LIMITS[limitSpinner.selectedItemPosition.coerceIn(0, LIMITS.size - 1)].toFloat())
        e.putInt("dark", darkSpinner.selectedItemPosition)
        e.putInt("range", rangeSpinner.selectedItemPosition)
        e.putInt("ymode", yModeSpinner.selectedItemPosition)
        e.putFloat("axismax", currentAxisMax().toFloat())
        e.putString("timezone", TIME_CODES[timeSpinner.selectedItemPosition.coerceIn(0, TIME_CODES.size - 1)])
        e.putInt("model", modelSpinner.selectedItemPosition)
        e.putString("targets", targets.joinToString("\n") { t ->
            listOf(t.name, t.raDeg.toString(), t.decDeg.toString(), t.pmRaCosDecMasYr.toString(), t.pmDecMasYr.toString(),
                t.type, t.source).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        })
        e.putInt("active", active)
        e.putBoolean("has_results", nightResults.isNotEmpty())
        e.apply()
    }

    private fun nights(): Int = nightsEdit.text.toString().toIntOrNull()?.coerceIn(1, MAX_NIGHTS) ?: 1

    private fun currentYMode() = if (yModeSpinner.selectedItemPosition == 1) YAxisMode.ALTITUDE else YAxisMode.AIRMASS

    private fun currentAxisMax(): Double = AXIS_MAX[axisSpinner.selectedItemPosition.coerceIn(0, AXIS_MAX.size - 1)]

    private fun currentLimit(): Double = LIMITS[limitSpinner.selectedItemPosition.coerceIn(0, LIMITS.size - 1)]

    /** Applies the airmass-axis choice to the diagrams (no recomputation needed). */
    private fun applyAxisMax() {
        val x = currentAxisMax()
        nightView.maxAirmass = x
        windowView.maxAirmass = x
        (full?.view as? NightChartView)?.maxAirmass = x
        (full?.view as? WindowChartView)?.maxAirmass = x
        val lim = currentLimit()
        axisNote.visibility = if (lim > x) View.VISIBLE else View.GONE
        axisNote.text = "The axis is extended to the airmass limit, X = ${ChartText.airmassLimit(lim)}."
    }

    private fun currentOptions() = ObservabilityOptions(
        airmassLimit = LIMITS[limitSpinner.selectedItemPosition.coerceIn(0, LIMITS.size - 1)],
        dark = DarkLimit.values()[darkSpinner.selectedItemPosition.coerceIn(0, DarkLimit.values().size - 1)],
        airmassModel = AirmassModel.values()[modelSpinner.selectedItemPosition.coerceIn(0, AirmassModel.values().size - 1)],
        fullDay = rangeSpinner.selectedItemPosition == 1,
    )

    /** Parses decimal degrees or sexagesimal "−29 15 24" / "-29:15:24". */
    private fun parseAngle(s: String): Double? {
        val t = s.trim().replace('−', '-').replace(',', '.')
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it }
        val neg = t.startsWith("-")
        val parts = t.trimStart('+', '-').split(Regex("""[\s:°'"′″dms]+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return null
        val v = parts.map { it.toDoubleOrNull() ?: return null }
        var x = v[0]
        if (v.size > 1) x += v[1] / 60.0
        if (v.size > 2) x += v[2] / 3600.0
        return if (neg) -x else x
    }

    private fun currentSite(): Site? {
        val pos = siteSpinner.selectedItemPosition
        if (pos > 0) return sites[pos - 1]
        val lat = parseAngle(latEdit.text.toString()) ?: return null
        val lon = parseAngle(lonEdit.text.toString()) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..360.0) return null
        val h = heightEdit.text.toString().toDoubleOrNull() ?: 0.0
        val zoneText = zoneEdit.text.toString().trim()
        val zone = runCatching { ZoneId.of(zoneText).id }.getOrDefault(ZoneId.systemDefault().id)
        val lonE = if (lon > 180.0) lon - 360.0 else lon
        return Site("custom", String.format(Locale.US, "Custom site %.4f°, %.4f°", lat, lonE), lat, lonE, h, zone)
    }

    private fun updateSiteUi() {
        val custom = siteSpinner.selectedItemPosition <= 0
        customBox.visibility = if (custom) View.VISIBLE else View.GONE
        val s = currentSite()
        siteInfo.text = if (s == null) {
            "Enter latitude and longitude (decimal degrees or d m s), height and time zone."
        } else {
            String.format(Locale.US, "Lat %.4f°  Lon %.4f°  Height %.0f m  ·  %s", s.latDeg, s.lonDeg, s.heightM, s.zoneId)
        }
    }

    private fun updateDateUi() {
        val n = nights()
        dateButton.text = "Start: ${DATE_FMT.format(startDate)}"
        dateInfo.text = if (n == 1) "One night (evening of ${DATE_FMT.format(startDate)})"
        else "$n nights: evenings of ${DATE_FMT.format(startDate)} → ${DATE_FMT.format(startDate.plusDays(n - 1L))}"
    }

    // ------------------------------------------------------------------ actions

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dialogTheme() = if (red) R.style.DialogTheme_Red else R.style.DialogTheme

    private fun pickDate() {
        DatePickerDialog(this, dialogTheme(), { _, y, m, d ->
            startDate = LocalDate.of(y, m + 1, d)
            updateDateUi()
            recomputeIfShown()
        }, startDate.year, startDate.monthValue - 1, startDate.dayOfMonth).show()
    }

    private fun resolveTarget() = resolveName(targetInput.text.toString())

    /**
     * Offline answers (coordinates, catalogue, names resolved before) are immediate; other names go to
     * NED and CDS Sesame on [netWorker]. Only the latest look-up is shown.
     */
    private fun resolveName(input: String) {
        val q = input.trim()
        if (q.isEmpty()) {
            targetInfo.text = "Type a name (e.g. M82, Cartwheel Galaxy) or coordinates, then press Add."
            return
        }
        hideKeyboard()
        targetInput.dismissDropDown()
        val gen = lookupGen.incrementAndGet()
        val cat = catalog
        if (cat != null) {
            TargetResolver(cat, resolvedCache).resolveOffline(q)?.let {
                handleOutcome(it)
                return
            }
        }
        targetInfo.text = "Looking up \"$q\" in NED and SIMBAD…"
        netWorker.execute {
            catalogReady.await(20, TimeUnit.SECONDS)
            val before = resolvedCache.size()
            val outcome = TargetResolver(catalog, resolvedCache).resolve(q)
            val cacheChanged = resolvedCache.size() != before || outcome is TargetResolver.Outcome.Found
            main.post {
                if (isFinishing || gen != lookupGen.get()) return@post
                if (cacheChanged) prefs.edit().putString("resolved", resolvedCache.serialize()).apply()
                handleOutcome(outcome)
            }
        }
    }

    private fun handleOutcome(outcome: TargetResolver.Outcome) {
        when (outcome) {
            is TargetResolver.Outcome.Found -> addTarget(outcome.target)
            is TargetResolver.Outcome.NotFound -> targetInfo.text = outcome.message
            is TargetResolver.Outcome.Ambiguous -> {
                targetInfo.text = outcome.message.removeSuffix(":") + " — choose one."
                val labels = outcome.choices.map { it.label }.toTypedArray()
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle(outcome.message)
                    .setItems(labels) { _, which ->
                        val c = outcome.choices[which]
                        val t = c.target
                        if (t != null) addTarget(t) else c.query?.let { resolveName(it) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** The night in progress: before 06:00 local time that is still the previous evening's night. */
    private fun currentNightDate(zone: ZoneId): LocalDate {
        val now = java.time.ZonedDateTime.now(zone)
        return if (now.toLocalTime().isBefore(LocalTime.of(6, 0))) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    private fun recomputeIfShown() {
        updateDateUi()
        savePrefs()
        if (nightResults.isNotEmpty()) compute()
    }

    /** Time scale chosen under Options → Time axis. */
    private fun displayZone(site: Site): ZoneId =
        when (val code = TIME_CODES[timeSpinner.selectedItemPosition.coerceIn(0, TIME_CODES.size - 1)]) {
            "site" -> site.zone
            "UT" -> ZoneId.of("UTC")
            "phone" -> ZoneId.systemDefault()
            else -> ZoneOffset.of(code)
        }

    /**
     * Computes the night diagram for all targets and the date-window diagram for the active one.
     * With [keepNight] the night already shown is kept (used when only the active target changes).
     */
    private fun compute(keepNight: Boolean = false) {
        if (targets.isEmpty()) {
            toast("Add a target first.")
            return
        }
        val site = currentSite() ?: run {
            toast("Complete the custom site coordinates first.")
            return
        }
        val list = targets.toList()
        val act = active.coerceIn(0, list.size - 1)
        val n = nights()
        val opts = currentOptions()
        val zone = displayZone(site)
        val start = startDate
        val night = if (keepNight && nightResults.isNotEmpty()) selectedNight.coerceIn(0, n - 1) else 0
        val gen = generation.incrementAndGet()
        progress.progress = 0
        progress.visibility = if (n > 1) View.VISIBLE else View.GONE
        status.text = "Computing ${if (n > 1) "$n nights" else "one night"} at ${site.name}…"
        worker.execute {
            val cs = list.map { ObservabilityCalculator(site, it, opts) }
            val w = if (n > 1) {
                cs[act].window(start, n, zone,
                    progress = { done, total ->
                        if (done % 4 == 0 || done == total) main.post { if (gen == generation.get()) progress.progress = done * 100 / total }
                    },
                    cancelled = { gen != generation.get() })
            } else {
                null
            }
            if (gen != generation.get() || (n > 1 && w == null)) return@execute
            val date = start.plusDays(night.toLong())
            val nr = cs.map { it.night(date) }
            main.post {
                if (gen != generation.get() || isFinishing) return@post
                calcs = cs
                calcZone = zone
                calcStart = start
                calcNights = n
                windowResult = w
                selectedNight = night
                showResults(nr)
                progress.visibility = View.GONE
                status.text = "${list.size} target${if (list.size > 1) "s" else ""} · ${site.name} · ${opts.airmassModel.label} airmass"
                savePrefs()
                requestSeason(site, list[act], opts, zone)
                if (scrollToResults) {
                    scrollToResults = false
                    scroll.post { scroll.smoothScrollTo(0, results.top) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ best time of year

    /** Scans the coming year for the active target (on the worker, after the diagrams); cached per input. */
    private fun requestSeason(site: Site, target: Target, opts: ObservabilityOptions, zone: ZoneId) {
        val start = currentNightDate(site.zone)
        val key = seasonKeyFor(site, target, opts)
        seasonTitle.text = "${target.name} · next 365 nights".uppercase(Locale.US)
        seasonCard.visibility = View.VISIBLE
        seasonLine.visibility = View.VISIBLE
        if (key == seasonKey && seasonResult != null) {
            seasonGen.incrementAndGet() // a scan for other inputs may still be running
            seasonRunningKey = ""
            if (zone != seasonZone || seasonView.visibility != View.VISIBLE) showSeason(seasonResult!!, zone)
            return
        }
        if (key == seasonRunningKey) return // the same scan is already running
        val gen = seasonGen.incrementAndGet()
        seasonStatus.text = "Scanning the next 365 nights…"
        seasonSummary.text = ""
        seasonView.visibility = View.GONE
        seasonRunningKey = key
        worker.execute {
            val r = SeasonPlanner(site, target, opts).scan(start, 365,
                progress = { done, total ->
                    // Progress bar only when the user is waiting for the best window.
                    if (done % 15 == 0 || done == total) main.post {
                        if (gen == seasonGen.get() && pendingBestWindow) {
                            progress.visibility = View.VISIBLE
                            progress.progress = done * 100 / total
                        }
                    }
                },
                cancelled = { gen != seasonGen.get() })
                ?: return@execute
            main.post {
                if (gen != seasonGen.get() || isFinishing) return@post
                seasonKey = key
                seasonRunningKey = ""
                showSeason(r, zone)
                if (pendingBestWindow) {
                    pendingBestWindow = false
                    progress.visibility = View.GONE
                    applyBestWindow(r)
                }
            }
        }
    }

    private fun showSeason(r: SeasonResult, zone: ZoneId) {
        seasonResult = r
        seasonZone = zone
        seasonPicked = null
        seasonView.visibility = View.VISIBLE
        seasonView.displayZone = zone
        seasonView.result = r
        seasonStatus.text = "Tap or drag on the chart to read a night. \"Go to …\" shows a night of your choice in the " +
            "diagrams above (it starts at the night you tapped)."
        seasonSummary.text = Summaries.season(r, zone)
        val best = Summaries.suggestedWindow(r)
        goNightButton.text = "Go to a night…"
        full?.refresh()
    }

    private fun pickSeasonNight(index: Int) {
        val r = seasonResult ?: return
        if (index !in r.nights.indices) return
        seasonPicked = index
        seasonView.cursor = index
        goNightButton.text = "Go to ${DateTimeFormatter.ofPattern("d MMM", Locale.US).format(r.nights[index].date)}…"
    }

    /** Inputs that determine the year scan (the display zone only changes how times are printed). */
    private fun seasonKeyFor(site: Site, target: Target, opts: ObservabilityOptions): String =
        listOf(site.latDeg, site.lonDeg, site.heightM, target.raDeg, target.decDeg, target.pmRaCosDecMasYr,
            target.pmDecMasYr, opts.airmassLimit, opts.dark, opts.airmassModel, currentNightDate(site.zone)).joinToString("|")

    /** "Compute Best Observability Window": scan the year for the active target if needed, then use its best window. */
    private fun computeBestWindow() {
        if (targets.isEmpty()) {
            toast("Add a target first.")
            return
        }
        val site = currentSite() ?: run {
            toast("Complete the custom site coordinates first.")
            return
        }
        val target = targets[active.coerceIn(0, targets.size - 1)]
        val opts = currentOptions()
        val r = seasonResult
        if (r != null && seasonKey == seasonKeyFor(site, target, opts)) {
            applyBestWindow(r)
            return
        }
        pendingBestWindow = true
        status.text = "Finding the best observability window for ${target.name} in the next 365 nights…"
        requestSeason(site, target, opts, displayZone(site))
    }

    /** Date window = the first best observability window (from tonight if it is in progress), then compute. */
    private fun applyBestWindow(r: SeasonResult) {
        val run = Summaries.suggestedWindow(r)
        if (run == null) {
            status.text = "${r.target.name}: no observability window in the next 365 nights with these options."
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("No best observability window")
                .setMessage(Summaries.season(r, seasonZone))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val first = if (run.ongoing) r.nights.first().date else run.firstDate
        val n = (run.lastDate.toEpochDay() - first.toEpochDay() + 1).toInt().coerceIn(1, MAX_NIGHTS)
        startDate = first
        nightsEdit.setText(n.toString())
        updateDateUi()
        toast("Best observability window: ${Summaries.dateRange(first, first.plusDays(n - 1L))} ($n nights)")
        scrollToResults = true
        compute()
    }

    private fun goToPickedNight() {
        val r = seasonResult ?: return
        val k = seasonPicked ?: return
        showNightOf(r.nights[k].date)
    }

    /** "Go to a night…": a date picker, starting at the night tapped on the year chart (or the current start). */
    private fun pickNightToShow() {
        val initial = seasonPicked?.let { seasonResult?.nights?.getOrNull(it)?.date } ?: startDate
        DatePickerDialog(this, dialogTheme(), { _, y, m, d -> showNightOf(LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply { setTitle("Show the night of…") }.show()
    }

    /** Makes [date] the first night of the date window, recomputes, and scrolls up to the night diagram. */
    private fun showNightOf(date: LocalDate) {
        startDate = date
        updateDateUi()
        if (nightResults.isNotEmpty()) {
            scrollToResults = true
            compute()
        } else {
            savePrefs()
        }
        toast("Night of ${DATE_FMT.format(date)}")
    }

    private fun showResults(nr: List<NightResult>) {
        results.visibility = View.VISIBLE
        val w = windowResult
        windowCard.visibility = if (w != null) View.VISIBLE else View.GONE
        windowLine.visibility = windowCard.visibility
        if (w != null) {
            windowTitle.text = "${w.target.name} · ${w.nights.size} night${if (w.nights.size > 1) "s" else ""}".uppercase(Locale.US)
            windowView.result = w
            windowView.selected = selectedNight
            windowSummary.text = Summaries.window(w)
        } else if (full?.kind == FullKind.WINDOW) {
            full?.dialog?.dismiss()
        }
        showNight(nr)
    }

    private fun showNight(nr: List<NightResult>) {
        nightResults = nr
        val a = active.coerceIn(0, nr.size - 1)
        nightView.displayZone = calcZone
        nightView.mode = currentYMode()
        nightView.active = a
        nightView.results = nr
        skyView.displayZone = calcZone
        skyView.active = a
        skyView.results = nr
        val date = nr.first().date
        navLabel.text = if (calcNights > 1) "${DATE_FMT.format(date)}  (${selectedNight + 1}/$calcNights)" else DATE_FMT.format(date)
        showNightDetails()
        full?.refresh()
    }

    private fun showNightDetails() {
        val nr = nightResults
        if (nr.isEmpty()) return
        val a = active.coerceIn(0, nr.size - 1)
        fillTable(nr)
        nightSummary.text = Summaries.night(nr[a], calcZone)
    }

    private fun fillTable(nr: List<NightResult>) {
        table.removeAllViews()
        val zone = calcZone
        fun cell(s: String, color: Int = pal.textSecondary, bold: Boolean = false, alignEnd: Boolean = false) =
            text(s, 13f, color, bold).apply {
                setPadding(dp(4), dp(3), dp(10), dp(3))
                if (alignEnd) gravity = Gravity.END
            }
        val lim = ChartText.airmassLimit(nr.first().options.airmassLimit)
        val head = TableRow(this)
        for (h in listOf("", "Target", "Best", "X min", "Alt", "Hours", "Moon↓ h", "X ≤ $lim from–to", "Moon sep")) {
            head.addView(cell(h, pal.textMuted, true))
        }
        table.addView(head)
        for (row in VisibilityTable.rows(nr)) {
            val tr = TableRow(this).apply { setOnClickListener { setActive(row.index) } }
            tr.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(pal.seriesColor(row.index))
                    cornerRadius = dp(2).toFloat()
                }
            }, TableRow.LayoutParams(dp(14), dp(5)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(4)
            })
            val isActive = row.index == active
            val nameColor = if (isActive) pal.textPrimary else pal.textSecondary
            tr.addView(cell(row.result.target.name.take(22), nameColor, isActive))
            tr.addView(cell(row.bestTime?.let { ChartText.hm(it, zone) } ?: "—"))
            tr.addView(cell(if (row.bestAirmass.isFinite()) String.format(Locale.US, "%.2f", row.bestAirmass) else "—", alignEnd = true))
            tr.addView(cell(if (row.bestAltitude.isNaN()) "—" else String.format(Locale.US, "%.0f°", row.bestAltitude), alignEnd = true))
            tr.addView(cell(String.format(Locale.US, "%.1f", row.observableHours), alignEnd = true))
            tr.addView(cell(String.format(Locale.US, "%.1f", row.observableMoonDownHours), alignEnd = true))
            tr.addView(cell(if (row.firstObservable != null && row.lastObservable != null)
                "${ChartText.hm(row.firstObservable, zone)}–${ChartText.hm(row.lastObservable, zone)}" else "—"))
            tr.addView(cell(if (row.minMoonSeparation.isNaN()) "Moon down" else String.format(Locale.US, "%.0f°", row.minMoonSeparation),
                alignEnd = true))
            table.addView(tr)
        }
    }

    private fun selectNight(index: Int) {
        val cs = calcs
        if (cs.isEmpty()) return
        if (index < 0 || index >= calcNights) {
            // ◀ on the first night or ▶ on the last: slide the date window by one night.
            startDate = calcStart.plusDays(if (index < 0) -1L else 1L)
            selectedNight = index.coerceIn(0, calcNights - 1)
            updateDateUi()
            compute(keepNight = true)
            return
        }
        selectedNight = index
        windowView.selected = index
        (full?.view as? WindowChartView)?.selected = index
        val gen = generation.get()
        val date = calcStart.plusDays(index.toLong())
        worker.execute {
            val nr = cs.map { it.night(date) }
            main.post { if (gen == generation.get() && selectedNight == index) showNight(nr) }
        }
    }

    // ------------------------------------------------------------------ full screen

    private enum class FullKind { NIGHT, SKY, WINDOW, SEASON }

    /** A diagram shown full screen in a dialog; it follows the data of the page (night changes, new results). */
    private inner class FullScreen(val kind: FullKind, val dialog: Dialog, val view: View, val title: TextView) {
        fun refresh() {
            when (kind) {
                FullKind.NIGHT -> (view as NightChartView).let {
                    if (nightResults.isEmpty()) return dialog.dismiss()
                    it.displayZone = calcZone
                    it.mode = currentYMode()
                    it.maxAirmass = currentAxisMax()
                    it.active = active.coerceIn(0, nightResults.size - 1)
                    it.results = nightResults
                }
                FullKind.SKY -> (view as SkyChartView).let {
                    if (nightResults.isEmpty()) return dialog.dismiss()
                    it.displayZone = calcZone
                    it.active = active.coerceIn(0, nightResults.size - 1)
                    it.results = nightResults
                }
                FullKind.WINDOW -> (view as WindowChartView).let {
                    val w = windowResult ?: return dialog.dismiss()
                    it.maxAirmass = currentAxisMax()
                    it.result = w
                    it.selected = selectedNight
                }
                FullKind.SEASON -> (view as SeasonChartView).let {
                    val r = seasonResult ?: return dialog.dismiss()
                    if (it.result !== r) {
                        it.displayZone = seasonZone
                        it.result = r
                    }
                }
            }
            title.text = when (kind) {
                FullKind.NIGHT, FullKind.SKY -> navLabel.text
                FullKind.WINDOW -> "Date window · ${windowResult?.target?.name ?: ""}"
                FullKind.SEASON -> "Best time of year · ${seasonResult?.target?.name ?: ""}"
            }
        }
    }

    private fun barButton(label: String, description: String, onClick: () -> Unit) = text(label, 24f, fullAccent(), true).apply {
        setPadding(dp(14), dp(4), dp(14), dp(4))
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun openFull(kind: FullKind) {
        if (full != null) return
        val ready = when (kind) {
            FullKind.NIGHT, FullKind.SKY -> nightResults.isNotEmpty()
            FullKind.WINDOW -> windowResult != null
            FullKind.SEASON -> seasonResult != null
        }
        if (!ready) {
            toast("Still computing: try again in a moment.")
            return
        }
        val d = Dialog(this, if (red) R.style.FullScreenTheme_Red else R.style.FullScreenTheme)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
        }
        val bar = row().apply { setPadding(dp(10), dp(2), dp(2), dp(2)) }
        val title = text("", 14f, pal.textPrimary, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(title, weighted())
        var goButton: TextView? = null
        if (kind == FullKind.NIGHT || kind == FullKind.SKY) {
            bar.addView(barButton("◀", "Previous night") { selectNight(selectedNight - 1) })
            bar.addView(barButton("▶", "Next night") { selectNight(selectedNight + 1) })
        }
        if (kind == FullKind.SEASON) {
            goButton = text("", 15f, fullInk(), true).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(fullAccent())
                    cornerRadius = dp(18).toFloat()
                }
                visibility = View.GONE
                setOnClickListener {
                    d.dismiss()
                    goToPickedNight()
                }
            }
            bar.addView(goButton)
        }
        bar.addView(barButton("⟳", "Rotate the screen") {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        })
        bar.addView(barButton("✕", "Close full screen") { d.dismiss() })
        root.addView(bar)
        val view: View = when (kind) {
            FullKind.NIGHT -> NightChartView(this).apply {
                palette = pal
                fullScreen = true
                onCursor = { skyView.cursor = it }
            }
            FullKind.SKY -> SkyChartView(this).apply {
                palette = pal
                fullScreen = true
            }
            FullKind.WINDOW -> WindowChartView(this).apply {
                palette = pal
                fullScreen = true
                onNightSelected = { selectNight(it) }
            }
            FullKind.SEASON -> SeasonChartView(this).apply {
                palette = pal
                fullScreen = true
                onNightSelected = { k ->
                    pickSeasonNight(k)
                    seasonResult?.nights?.getOrNull(k)?.let { n ->
                        goButton?.text = "Go to ${DateTimeFormatter.ofPattern("d MMM", Locale.US).format(n.date)} ›"
                        goButton?.visibility = View.VISIBLE
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Keep clear of display cutouts (and of the system bars while they are shown).
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                v.setPadding(c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0, c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
            }
            insets
        }
        d.setContentView(root)
        val previousOrientation = requestedOrientation
        d.setOnDismissListener {
            requestedOrientation = previousOrientation
            full = null
        }
        val f = FullScreen(kind, d, view, title)
        full = f
        f.refresh()
        if (kind == FullKind.NIGHT) (view as NightChartView).showCursor(nightView.cursorTime)
        if (kind == FullKind.SKY) (view as SkyChartView).cursor = skyView.cursor
        if (kind == FullKind.SEASON) (view as SeasonChartView).cursor = seasonPicked
        d.show()
        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 28) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false)
                w.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Night vision (red)").apply {
            isCheckable = true
            isChecked = red
        }
        m.menu.add(0, 3, 1, "Instructions")
        m.menu.add(0, 4, 2, "Disclaimer")
        m.menu.add(0, 5, 3, "Feedback & new versions")
        m.menu.add(0, 2, 4, "About & data sources")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    savePrefs()
                    prefs.edit().putBoolean("red", !red).apply()
                    recreate()
                }
                2 -> showAbout()
                3 -> showInstructions()
                4 -> showDisclaimer()
                5 -> showFeedback()
            }
            true
        }
        m.show()
    }

    private fun showAbout() {
        val msg = """
            $APP_NAME ($APP_TITLE), version ${appVersion()}, designed and written by $AUTHOR. Free, for the astronomical community.

            Observability diagrams for up to $MAX_TARGETS targets: airmass or altitude against time for one night with the Moon's track and phase, a visibility table sorted by the time of minimum airmass, sky tracks, the best time of year for the active target, and an overview over a date window of up to $MAX_NIGHTS nights. The airmass axis (1.0 to 2.5 by default) can be set under Options. Double-tap any diagram (or use ⤢) to see it full screen.

            Computation (on the device, offline):
            • Target: J2000 position → proper motion → annual aberration → IAU 1976 precession → IAU 1980 nutation (apparent place, ~1").
            • Sun: Meeus ch. 25 (0.01°). Moon: Meeus ch. 47 (ELP-2000/82 truncated, ~10"), topocentric; age from the last geocentric new Moon.
            • Airmass: Young (1994) by default; Hardie (1962) and sec z available.
            • Validated against astropy/ERFA: target and Moon positions within 5", Sun within 25", rise/set and twilight times within 2 s.
            • UT1 is taken equal to UTC; heights are above sea level.

            Data:
            • OpenNGC by Mattia Verga (NGC/IC/Messier and addendum), CC BY-SA 4.0.
            • HYG star database by David Nash (named stars), CC BY-SA 4.0.
            • Observatory coordinates: the astropy-data site registry, with additions.
            • Online name resolution: NED, the NASA/IPAC Extragalactic Database (Caltech/IPAC), and CDS Sesame (SIMBAD, NED, VizieR), Strasbourg. Names found online are remembered on the phone.

            Best time of year: hours per night in dark time with airmass ≤ limit over the next 365 nights (10-minute steps); best observability window = the nights with at least 80 % of the year's maximum; darkest nights = Moon below the horizon for at least 80 % of that time.

            Project page (source code, new versions, feedback): $PROJECT_URL
        """.trimIndent()
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("About $APP_NAME")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun appVersion(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: ""
    }.getOrDefault("")

    private fun showDisclaimer() {
        val msg = """
            $APP_NAME · $APP_TITLE
            Version ${appVersion()}

            Designed and written by $AUTHOR.

            $APP_NAME is free. It is made available to the astronomical community.

            The app is provided "as is", without warranty of any kind. Positions, times and airmasses are computed on the phone with standard algorithms; compared with astropy they agree to within a few arcseconds (25″ for the Sun) and 2 seconds of time (see About). They are meant for planning observations and do not replace the official tools, ephemerides and schedules of each observatory.

            Names that are not in the built-in catalogue are resolved online by NED and CDS Sesame (SIMBAD); their answers and availability depend on those services. Always check a target's coordinates before observing.

            The author accepts no responsibility for lost observing time or for any other consequence of the use of the app.

            Data: OpenNGC (M. Verga) and the HYG database (D. Nash), CC BY-SA 4.0; observatory list from the astropy-data site registry; name resolution by NED (NASA/IPAC) and CDS Sesame (Strasbourg).

            Source code (MIT licence), new versions and feedback: $PROJECT_URL
        """.trimIndent()
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Disclaimer")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFeedback() {
        val msg = """
            $APP_NAME is shared on its project page on GitHub:
            $PROJECT_URL

            • To report a problem or suggest an improvement, choose "Report or suggest" (a free GitHub account is needed). For a problem, please give the target, the site, the date and, if you can, a screenshot.
            • New versions are on the project's Releases page ("Newest version"). Download the .apk file on the phone and install it over this one; your targets and settings are kept.

            This is version ${appVersion()}.
        """.trimIndent()
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Feedback & new versions")
            .setMessage(msg)
            .setPositiveButton("Report or suggest") { _, _ -> openUrl("$PROJECT_URL/issues/new/choose") }
            .setNeutralButton("Newest version") { _, _ -> openUrl("$PROJECT_URL/releases/latest") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("No web browser found. The address is $url")
        }
    }

    private fun showInstructions() {
        val msg = """
            The page is divided into numbered sections by coloured lines: 1 Targets and site, 2 Options, 3 Compute, 4 Date window, followed by the results. In night vision everything is red.

            1. Targets
            Type a name (M82, NGC 7531, Andromeda Galaxy, Cartwheel Galaxy…) or coordinates (ICRS/J2000, e.g. 23:14:48.5 −43:35:60 or 348.702 −43.600) and press Add. Up to $MAX_TARGETS targets are compared on one diagram. Tap a target to make it the active one (used by the "Best time of year" and "Date window" diagrams); ✕ removes it. Names not in the built-in catalogue are looked up online in NED and SIMBAD, so the phone must be online the first time; when a name fits several objects you choose from a list.

            2. Observing site
            Choose an observatory, or "Custom location…" with latitude and longitude in degrees (+ north, + east), height and time zone, or "Use device location".

            3. Options
            • Airmass limit: "observable" means dark AND airmass at or below this limit.
            • Dark time: the Sun below −18°, −12° or −6°.
            • Night range: sunset to sunrise, or the full day (noon to noon).
            • Y axis: airmass or altitude. Airmass axis: how far the airmass axis goes (1.0 to 2.5 by default); it always reaches the airmass limit.
            • Time axis: the site's local time (with daylight saving), UT, this phone's time zone, or a fixed offset from UTC−12 to UTC+14 (e.g. UTC−3, UTC+5:30). Airmass model: Young (default), Hardie or sec z.

            4. Compute
            • "Compute Observability" (the large blue button) draws the diagrams for the date window (step 5).
            • "Compute Best Observability Window" (amber button) first finds when the active target is best observed during the next 365 nights (the nights with at least 80 % of the year's maximum of observable hours), puts that period in the date window and then computes it.

            5. Date window
            The start is the evening of a date; the window covers 1–366 nights. "Tonight" picks the current night (before 06:00 that is still the previous evening's night). Once the diagrams are shown, changing the dates or any option recomputes them.

            Reading the results
            • Night diagram: one curve per target, thicker where it is observable; the dotted curve is the Moon, with its phase; the background shows day, twilight and night; LST runs along the top. Touch and drag to read the values: each target's airmass next to its point on the curve, and the time, altitude, azimuth and Moon distance in the box. ◀ ▶ change the night.
            • Visibility table: the targets in order of their best moment; tap a row to make that target active.
            • Sky tracks: the paths on the sky, zenith at the centre, north up, east left.
            • Best time of year: observable hours per night over the next year (darker part = Moon down, lighter part = Moon up), with the best observability window shaded, the opposition and the best single night. "Go to a night…" shows any night you choose.
            • Date window: one column per night, coloured by airmass, hatched where the Moon is up; tap a column to show that night.

            Full screen
            Press the bright "⤢ FULL SCREEN" button above a diagram (green; red in night vision), or double-tap the diagram. ⟳ turns the screen to landscape, ◀ ▶ change the night, ✕ or Back closes.

            Other
            • Save PNG and Share export the diagrams (to Pictures/$APP_NAME).
            • ⋮ → Night vision (red) protects your dark adaptation at the telescope.
            • Feedback (top of the page, or ⋮): report a problem, suggest an improvement, or get the newest version.
            • Everything is computed on the phone; only the name look-ups need the internet.
        """.trimIndent()
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Instructions")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------------ location

    private fun requestLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            fetchLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) fetchLocation()
            else toast("Location permission denied; enter the coordinates by hand.")
        }
    }

    private fun applyLocation(loc: Location) {
        latEdit.setText(String.format(Locale.US, "%.5f", loc.latitude))
        lonEdit.setText(String.format(Locale.US, "%.5f", loc.longitude))
        if (loc.hasAltitude()) heightEdit.setText(loc.altitude.roundToInt().toString())
        zoneEdit.setText(ZoneId.systemDefault().id, false)
        updateSiteUi()
        recomputeIfShown()
        toast(String.format(Locale.US, "Location set (±%.0f m).", loc.accuracy))
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) {
            toast("Location is turned off on this device.")
            return
        }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) {
            applyLocation(last)
            return
        }
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.first()
        }
        toast("Getting the current location…")
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                when {
                    loc != null -> applyLocation(loc)
                    last != null -> applyLocation(last)
                    else -> toast("No location fix available.")
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = applyLocation(location)

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    // ------------------------------------------------------------------ export

    private fun exportCharts(share: Boolean) {
        val bitmaps = ArrayList<Pair<String, Bitmap>>()
        val base = (targets.getOrNull(active)?.name ?: "target").replace(Regex("[^A-Za-z0-9_-]+"), "_")
        val date = nightResults.firstOrNull()?.date?.toString() ?: ""
        val who = if (targets.size > 1) "${targets.size}targets" else base
        nightView.toBitmap()?.let { bitmaps.add("observability_${who}_$date.png" to it) }
        skyView.toBitmap()?.let { bitmaps.add("skytracks_${who}_$date.png" to it) }
        if (windowCard.visibility == View.VISIBLE) {
            windowView.toBitmap()?.let { bitmaps.add("observability_${base}_${calcStart}_${calcNights}n.png" to it) }
        }
        if (seasonCard.visibility == View.VISIBLE && seasonView.visibility == View.VISIBLE) {
            seasonView.toBitmap()?.let { bitmaps.add("besttime_${base}_${seasonResult?.nights?.firstOrNull()?.date ?: ""}.png" to it) }
        }
        if (bitmaps.isEmpty()) {
            toast("Compute a diagram first.")
            return
        }
        val uris = ArrayList<Uri>()
        val paths = ArrayList<String>()
        for ((name, bmp) in bitmaps) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$APP_NAME")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uris.add(uri)
                paths.add("Pictures/$APP_NAME/$name")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                paths.add(f.absolutePath)
            }
        }
        if (share && uris.isNotEmpty()) {
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            intent.type = "image/png"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share observability diagrams"))
        } else {
            toast("Saved: " + paths.joinToString(", "))
        }
    }
}
