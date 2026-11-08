package cl.astro.observability.chart

/**
 * Minimal drawing surface used by the chart renderers. The app implements it on an Android Canvas;
 * the validation tools implement it on Java2D to render PNG previews of exactly the same layout code.
 * Coordinates are pixels, y grows downwards, colours are ARGB ints.
 */
interface DrawSink {
    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int)
    fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, dash: FloatArray? = null)

    /** Polyline through points [from, to) of xs/ys. */
    fun polyline(xs: FloatArray, ys: FloatArray, from: Int, to: Int, color: Int, width: Float, dash: FloatArray? = null)
    fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int)
    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float)

    /** Text with its baseline at y. */
    fun text(s: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign = TextAlign.LEFT, bold: Boolean = false)

    /** Text rotated by −90° (reads bottom → top); (x, y) is the start of the baseline. */
    fun textUp(s: String, x: Float, y: Float, sizePx: Float, color: Int)
    fun textWidth(s: String, sizePx: Float, bold: Boolean = false): Float
    fun save()
    fun clipRect(l: Float, t: Float, r: Float, b: Float)
    fun restore()
}

enum class TextAlign { LEFT, CENTER, RIGHT }

object Colors {
    fun alpha(c: Int, a: Float): Int {
        val na = (((c ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt()
        return (na shl 24) or (c and 0xFFFFFF)
    }

    /** Linear blend in sRGB: t = 0 → a, t = 1 → b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * u + 0.5f).toInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Composites an ARGB colour over an opaque one. */
    fun over(top: Int, bottom: Int): Int {
        val a = ((top ushr 24) and 0xFF) / 255f
        return mix(bottom or (0xFF shl 24), top or (0xFF shl 24), a)
    }
}

/** Colours of the diagrams. Two selected sets: the default dark theme and a red night-vision theme. */
class ChartPalette(
    val surface: Int,
    val day: Int,
    val civil: Int,
    val nautical: Int,
    val astro: Int,
    val night: Int,
    val grid: Int,
    val axis: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textMuted: Int,
    val target: Int,
    val moon: Int,
    val limit: Int,
    val now: Int,
    val moonLit: Int,
    val moonDark: Int,
    val moonOverlay: Int,
    /** Sequential ramp for airmass: index 0 = best (X = 1), last = worst. One hue, light → dark. */
    val ramp: IntArray,
    val barMoonDown: Int,
    val barMoonUp: Int,
    val tooltip: Int,
    /**
     * Categorical colours for up to 8 targets, in a fixed order validated for colour-vision deficiency
     * (adjacent pairs ΔE ≥ 8). Target i always keeps colour i.
     */
    val series: IntArray,
    /** Optional dash pattern per series, in dp (null = solid). Used in the red theme, where hue cannot help. */
    val seriesDash: Array<FloatArray?>,
) {
    fun seriesColor(i: Int): Int = series[i % series.size]
    fun seriesDash(i: Int, dp: Float): FloatArray? = seriesDash[i % seriesDash.size]?.let { d -> FloatArray(d.size) { d[it] * dp } }

    /** Ramp colour for t in [0, 1], quantised to [levels] steps so that equal cells merge when drawn. */
    fun rampAt(t: Float, levels: Int = 32): Int {
        val q = (kotlin.math.round(t.coerceIn(0f, 1f) * (levels - 1)) / (levels - 1)).toFloat()
        val pos = q * (ramp.size - 1)
        val i = pos.toInt().coerceAtMost(ramp.size - 2)
        return Colors.mix(ramp[i], ramp[i + 1], pos - i)
    }

    companion object {
        val DARK = ChartPalette(
            surface = 0xFF1A1A19.toInt(),
            day = 0xFF4A4944.toInt(),
            civil = 0xFF3A3936.toInt(),
            nautical = 0xFF2D2C2A.toInt(),
            astro = 0xFF232321.toInt(),
            night = 0xFF121211.toInt(),
            grid = 0x26FFFFFF,
            axis = 0x73FFFFFF,
            textPrimary = 0xFFF0EFEC.toInt(),
            textSecondary = 0xFFC3C2B7.toInt(),
            textMuted = 0xFF8F8E86.toInt(),
            target = 0xFF3987E5.toInt(),
            moon = 0xFFD9D4C3.toInt(),
            limit = 0xFFC3C2B7.toInt(),
            now = 0xFFE66767.toInt(),
            moonLit = 0xFFE9E6DC.toInt(),
            moonDark = 0xFF3F3E3A.toInt(),
            moonOverlay = 0xFFFFFFFF.toInt(),
            ramp = intArrayOf(
                0xFFCDE2FB.toInt(), 0xFF9EC5F4.toInt(), 0xFF6DA7EC.toInt(), 0xFF3987E5.toInt(),
                0xFF256ABF.toInt(), 0xFF184F95.toInt(), 0xFF0D366B.toInt(),
            ),
            barMoonDown = 0xFF3987E5.toInt(),
            barMoonUp = 0xFF86B6EF.toInt(),
            tooltip = 0xEE2A2A28.toInt(),
            series = intArrayOf(
                0xFF3987E5.toInt(), 0xFFD95926.toInt(), 0xFF199E70.toInt(), 0xFFC98500.toInt(),
                0xFFD55181.toInt(), 0xFF008300.toInt(), 0xFF9085E9.toInt(), 0xFFE66767.toInt(),
            ),
            seriesDash = arrayOfNulls(8),
        )

        val RED = ChartPalette(
            surface = 0xFF120000.toInt(),
            day = 0xFF3D0B0B.toInt(),
            civil = 0xFF2E0808.toInt(),
            nautical = 0xFF230606.toInt(),
            astro = 0xFF1A0404.toInt(),
            night = 0xFF0C0101.toInt(),
            grid = 0x33FF2020,
            axis = 0x80FF3030.toInt(),
            textPrimary = 0xFFFF5A50.toInt(),
            textSecondary = 0xFFD04038.toInt(),
            textMuted = 0xFF8C2A25.toInt(),
            target = 0xFFFF3B30.toInt(),
            moon = 0xFFB8322A.toInt(),
            limit = 0xFFC03A32.toInt(),
            now = 0xFFFF8A80.toInt(),
            moonLit = 0xFFE04A40.toInt(),
            moonDark = 0xFF3A0A08.toInt(),
            moonOverlay = 0xFFFF4A40.toInt(),
            ramp = intArrayOf(
                0xFFFF9A90.toInt(), 0xFFFF6B60.toInt(), 0xFFF04538.toInt(), 0xFFD12A20.toInt(),
                0xFFA81C14.toInt(), 0xFF7A100A.toInt(), 0xFF4D0805.toInt(),
            ),
            barMoonDown = 0xFFE03028.toInt(),
            barMoonUp = 0xFF8C2A25.toInt(),
            tooltip = 0xEE240302.toInt(),
            series = intArrayOf(
                0xFFFF3B30.toInt(), 0xFFFF8A70.toInt(), 0xFFD02A20.toInt(), 0xFFFFB0A0.toInt(),
                0xFFE85A4A.toInt(), 0xFFB01C14.toInt(), 0xFFFF6A58.toInt(), 0xFFC84A40.toInt(),
            ),
            seriesDash = arrayOf(
                null, floatArrayOf(10f, 4f), floatArrayOf(14f, 4f, 3f, 4f), floatArrayOf(5f, 3f),
                floatArrayOf(14f, 3f, 3f, 3f, 3f, 3f), floatArrayOf(8f, 6f), floatArrayOf(18f, 5f), floatArrayOf(3f, 4f, 9f, 4f),
            ),
        )
    }
}
