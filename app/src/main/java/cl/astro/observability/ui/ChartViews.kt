package cl.astro.observability.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import cl.astro.observability.astro.NightResult
import cl.astro.observability.astro.SeasonResult
import cl.astro.observability.astro.WindowResult
import cl.astro.observability.chart.ChartPalette
import cl.astro.observability.chart.DrawSink
import cl.astro.observability.chart.NightChartRenderer
import cl.astro.observability.chart.SeasonChartRenderer
import cl.astro.observability.chart.SkyChartRenderer
import cl.astro.observability.chart.TextAlign
import cl.astro.observability.chart.WindowChartRenderer
import cl.astro.observability.chart.YAxisMode
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

/** [DrawSink] on an Android [Canvas]. */
class CanvasSink(private val c: Canvas) : DrawSink {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun setStroke(color: Int, width: Float, dash: FloatArray?) {
        stroke.color = color
        stroke.strokeWidth = width
        stroke.pathEffect = if (dash != null) DashPathEffect(dash, 0f) else null
        stroke.strokeCap = if (dash != null) Paint.Cap.BUTT else Paint.Cap.ROUND
    }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float) {
        setStroke(color, width, null)
        c.drawRect(l, t, r, b, stroke)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, dash: FloatArray?) {
        setStroke(color, width, dash)
        if (dash == null) {
            c.drawLine(x0, y0, x1, y1, stroke)
        } else {
            path.reset()
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            c.drawPath(path, stroke)
        }
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, from: Int, to: Int, color: Int, width: Float, dash: FloatArray?) {
        if (to - from < 2) return
        setStroke(color, width, dash)
        path.reset()
        path.moveTo(xs[from], ys[from])
        for (i in from + 1 until to) path.lineTo(xs[i], ys[i])
        c.drawPath(path, stroke)
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        if (n < 3) return
        fill.color = color
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        path.close()
        c.drawPath(path, fill)
    }

    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, r, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        setStroke(color, width, null)
        c.drawCircle(cx, cy, r, stroke)
    }

    private fun setText(size: Float, color: Int, bold: Boolean) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    override fun text(s: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {
        setText(sizePx, color, bold)
        textPaint.textAlign = when (align) {
            TextAlign.LEFT -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
        }
        c.drawText(s, x, y, textPaint)
    }

    override fun textUp(s: String, x: Float, y: Float, sizePx: Float, color: Int) {
        setText(sizePx, color, false)
        textPaint.textAlign = Paint.Align.LEFT
        c.save()
        c.rotate(-90f, x, y)
        c.drawText(s, x, y, textPaint)
        c.restore()
    }

    override fun textWidth(s: String, sizePx: Float, bold: Boolean): Float {
        textPaint.textSize = sizePx
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return textPaint.measureText(s)
    }

    override fun save() {
        c.save()
    }

    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {
        c.clipRect(l, t, r, b)
    }

    override fun restore() {
        c.restore()
    }
}

/**
 * Horizontal drags (after the touch slop) are taken by the chart; vertical ones are left to the
 * enclosing ScrollView so the page still scrolls when the finger starts on a chart. In a full-screen
 * view ([anyDirection]) every drag belongs to the chart. Also recognises a double tap.
 */
private class DragHelper(context: Context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private var downX = 0f
    private var downY = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    var anyDirection = false
    var horizontal = false
        private set
    var moved = false
        private set

    fun down(e: MotionEvent) {
        downX = e.x
        downY = e.y
        horizontal = false
        moved = false
    }

    /** Returns true once the gesture is a drag the chart should follow. */
    fun move(v: View, e: MotionEvent): Boolean {
        val dx = abs(e.x - downX)
        val dy = abs(e.y - downY)
        if (dx > slop || dy > slop) moved = true
        if (!horizontal && ((dx > slop && dx > dy) || (anyDirection && moved))) {
            horizontal = true
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return horizontal || anyDirection
    }

    /** Call on ACTION_UP; true when this tap completes a double tap. */
    fun isDoubleTap(e: MotionEvent): Boolean {
        if (moved) {
            lastTapTime = 0L
            return false
        }
        val t = e.eventTime
        val dbl = lastTapTime != 0L && t - lastTapTime <= doubleTapMs &&
            abs(e.x - lastTapX) < doubleTapSlop && abs(e.y - lastTapY) < doubleTapSlop
        if (dbl) {
            lastTapTime = 0L
        } else {
            lastTapTime = t
            lastTapX = e.x
            lastTapY = e.y
        }
        return dbl
    }
}

/** Size rule shared by the charts: an aspect ratio in the page, or all the space given in full screen. */
private fun View.chartSize(widthSpec: Int, heightSpec: Int, fullScreen: Boolean, aspect: Float, minHeightDp: Int,
                           maxScreenFraction: Float): IntArray {
    val w = View.MeasureSpec.getSize(widthSpec)
    if (fullScreen && View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
        return intArrayOf(w, View.MeasureSpec.getSize(heightSpec))
    }
    val dp = resources.displayMetrics.density
    val screenH = resources.displayMetrics.heightPixels
    val h = min((w * aspect).toInt(), (screenH * maxScreenFraction).toInt()).coerceAtLeast((minHeightDp * dp).toInt())
    return intArrayOf(w, h)
}

class NightChartView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private val renderer = NightChartRenderer(ChartPalette.DARK, dp, sp)
    private val drag = DragHelper(context)

    /** One result per target, all for the same site and night. */
    var results: List<NightResult> = emptyList()
        set(value) {
            field = value
            setCursor(null)
            invalidate()
        }
    var active: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var mode: YAxisMode = YAxisMode.AIRMASS
        set(value) {
            field = value
            invalidate()
        }

    /** Bottom of the airmass axis (raised to the airmass limit when that is larger). */
    var maxAirmass: Double
        get() = renderer.maxAirmass
        set(value) {
            renderer.maxAirmass = value
            invalidate()
        }
    var displayZone: ZoneId = ZoneId.of("UTC")
        set(value) {
            field = value
            invalidate()
        }
    var palette: ChartPalette = ChartPalette.DARK
        set(value) {
            field = value
            renderer.palette = value
            invalidate()
        }

    /** Notified when the touch cursor moves (null when released), e.g. to mirror it on the sky chart. */
    var onCursor: ((Long?) -> Unit)? = null

    /** Double tap: open the diagram full screen (null in the full-screen view itself). */
    var onExpand: (() -> Unit)? = null

    /** Fills the space it is given instead of keeping an aspect ratio; drags in any direction move the cursor. */
    var fullScreen = false
        set(value) {
            field = value
            drag.anyDirection = value
            requestLayout()
        }
    private var cursor: Long? = null

    private fun setCursor(c: Long?) {
        if (c == cursor) return
        cursor = c
        onCursor?.invoke(c)
    }

    init {
        contentDescription = "Night observability diagram"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = chartSize(widthMeasureSpec, heightMeasureSpec, fullScreen, 0.86f, 280, 0.8f)
        setMeasuredDimension(s[0], s[1])
    }

    override fun onDraw(canvas: Canvas) {
        if (results.isEmpty()) return
        renderer.draw(CanvasSink(canvas), width.toFloat(), height.toFloat(), results, active, mode, displayZone, cursor,
            System.currentTimeMillis())
    }

    /** Shows the cursor at an instant (used to carry the cursor into the full-screen view). */
    fun showCursor(t: Long?) {
        setCursor(t)
        invalidate()
    }

    val cursorTime: Long? get() = cursor

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (results.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag.down(e)
                setCursor(renderer.timeAt(e.x))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag.move(this, e)) {
                    setCursor(renderer.timeAt(e.x))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drag.moved) performClick()
                if (drag.isDoubleTap(e)) onExpand?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                setCursor(null)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Renders the current diagram (without cursor) into a bitmap of the view's size. */
    fun toBitmap(): Bitmap? {
        if (results.isEmpty() || width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(CanvasSink(Canvas(bmp)), width.toFloat(), height.toFloat(), results, active, mode, displayZone, null, null)
        return bmp
    }
}

/** Polar sky-track chart; mirrors the night diagram's cursor. */
class SkyChartView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private val renderer = SkyChartRenderer(ChartPalette.DARK, dp, sp)
    private val drag = DragHelper(context)

    var results: List<NightResult> = emptyList()
        set(value) {
            field = value
            cursor = null
            invalidate()
        }
    var active: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var displayZone: ZoneId = ZoneId.of("UTC")
        set(value) {
            field = value
            invalidate()
        }
    var palette: ChartPalette = ChartPalette.DARK
        set(value) {
            field = value
            renderer.palette = value
            invalidate()
        }
    var cursor: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Double tap: open the chart full screen. */
    var onExpand: (() -> Unit)? = null
    var fullScreen = false
        set(value) {
            field = value
            requestLayout()
        }

    init {
        contentDescription = "Sky tracks of the targets and the Moon"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = chartSize(widthMeasureSpec, heightMeasureSpec, fullScreen, 1.02f, 0, 0.85f)
        setMeasuredDimension(s[0], s[1])
    }

    override fun onDraw(canvas: Canvas) {
        if (results.isEmpty()) return
        renderer.draw(CanvasSink(canvas), width.toFloat(), height.toFloat(), results, active, displayZone, cursor)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (results.isEmpty() || onExpand == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> drag.down(e)
            MotionEvent.ACTION_MOVE -> drag.move(this, e)
            MotionEvent.ACTION_UP -> {
                if (!drag.moved) performClick()
                if (drag.isDoubleTap(e)) onExpand?.invoke()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun toBitmap(): Bitmap? {
        if (results.isEmpty() || width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(CanvasSink(Canvas(bmp)), width.toFloat(), height.toFloat(), results, active, displayZone, null)
        return bmp
    }
}

class WindowChartView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private val renderer = WindowChartRenderer(ChartPalette.DARK, dp, sp)
    private val drag = DragHelper(context)

    var result: WindowResult? = null
        set(value) {
            field = value
            invalidate()
        }
    var selected: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Airmass at the dark end of the colour scale (raised to the airmass limit when that is larger). */
    var maxAirmass: Double
        get() = renderer.maxAirmass
        set(value) {
            renderer.maxAirmass = value
            invalidate()
        }
    var palette: ChartPalette = ChartPalette.DARK
        set(value) {
            field = value
            renderer.palette = value
            invalidate()
        }

    /** Called with the index of the night the user tapped (or dragged to). */
    var onNightSelected: ((Int) -> Unit)? = null

    /** Double tap: open the diagram full screen. */
    var onExpand: (() -> Unit)? = null
    var fullScreen = false
        set(value) {
            field = value
            drag.anyDirection = value
            requestLayout()
        }

    init {
        contentDescription = "Observability over the date window"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = chartSize(widthMeasureSpec, heightMeasureSpec, fullScreen, 0.9f, 320, 0.8f)
        setMeasuredDimension(s[0], s[1])
    }

    override fun onDraw(canvas: Canvas) {
        val r = result ?: return
        renderer.draw(CanvasSink(canvas), width.toFloat(), height.toFloat(), r, selected)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (result == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag.down(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag.move(this, e)) renderer.nightAt(e.x)?.let { if (it != selected) selected = it }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drag.moved) renderer.nightAt(e.x)?.let { selected = it }
                if (!drag.moved || drag.horizontal) {
                    performClick()
                    onNightSelected?.invoke(selected)
                }
                if (drag.isDoubleTap(e)) onExpand?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun toBitmap(): Bitmap? {
        val r = result ?: return null
        if (width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(CanvasSink(Canvas(bmp)), width.toFloat(), height.toFloat(), r, selected)
        return bmp
    }
}

/** Year overview (best time of year); tap or drag to read a night, release to select it. */
class SeasonChartView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private val renderer = SeasonChartRenderer(ChartPalette.DARK, dp, sp)
    private val drag = DragHelper(context)

    var result: SeasonResult? = null
        set(value) {
            field = value
            cursor = null
            invalidate()
        }
    var displayZone: ZoneId = ZoneId.of("UTC")
        set(value) {
            field = value
            invalidate()
        }
    var palette: ChartPalette = ChartPalette.DARK
        set(value) {
            field = value
            renderer.palette = value
            invalidate()
        }

    /** Index of the night shown in the read-out (null = none). */
    var cursor: Int? = null
        set(value) {
            field = value
            committed = value
            invalidate()
        }

    /** Read-out to restore when a gesture is taken over by the page scroll. */
    private var committed: Int? = null

    private fun showReadout(i: Int?) {
        val keep = committed
        cursor = i
        committed = keep
    }

    /** Called with the night index when the finger is lifted after a tap or a drag. */
    var onNightSelected: ((Int) -> Unit)? = null
    var onExpand: (() -> Unit)? = null
    var fullScreen = false
        set(value) {
            field = value
            drag.anyDirection = value
            requestLayout()
        }

    init {
        contentDescription = "Observable hours per night over the coming year"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = chartSize(widthMeasureSpec, heightMeasureSpec, fullScreen, 0.72f, 280, 0.7f)
        setMeasuredDimension(s[0], s[1])
    }

    override fun onDraw(canvas: Canvas) {
        val r = result ?: return
        renderer.draw(CanvasSink(canvas), width.toFloat(), height.toFloat(), r, cursor, displayZone)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (result == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag.down(e)
                renderer.nightAt(e.x)?.let { showReadout(it) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag.move(this, e)) renderer.nightAt(e.x)?.let { if (it != cursor) showReadout(it) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drag.moved || drag.horizontal) {
                    performClick()
                    committed = cursor
                    cursor?.let { onNightSelected?.invoke(it) }
                } else {
                    showReadout(committed)
                }
                if (drag.isDoubleTap(e)) onExpand?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                showReadout(committed)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun toBitmap(): Bitmap? {
        val r = result ?: return null
        if (width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(CanvasSink(Canvas(bmp)), width.toFloat(), height.toFloat(), r, null, displayZone)
        return bmp
    }
}
