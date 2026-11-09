package cl.astro.observability

import cl.astro.observability.astro.NightResult
import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.chart.ChartPalette
import cl.astro.observability.chart.ChartText
import cl.astro.observability.chart.DrawSink
import cl.astro.observability.chart.NightChartRenderer
import cl.astro.observability.chart.TextAlign
import cl.astro.observability.chart.YAxisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The night-diagram cursor: each target's airmass is written next to its point on the curve, or, when that point
 * cannot be labelled, in the read-out box, so that every airmass is shown exactly once and nothing leaves the screen.
 */
class CursorReadoutTest {
    private val lasilla = Site("lasilla", "La Silla", -29.256667, -70.73, 2347.0, "America/Santiago")
    private val lapalma = Site("lapalma", "Roque de los Muchachos", 28.7606, -17.8792, 2326.0, "Atlantic/Canary")

    private class Drawn(val s: String, val x: Float, val y: Float, val size: Float, val color: Int, val bold: Boolean)

    /** Records the text drawn; widths are a fixed fraction of the font size per character. */
    private class RecordingSink : DrawSink {
        val texts = ArrayList<Drawn>()
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, dash: FloatArray?) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, from: Int, to: Int, color: Int, width: Float, dash: FloatArray?) {}
        override fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {}
        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {}
        override fun text(s: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {
            texts.add(Drawn(s, x, y, sizePx, color, bold))
        }
        override fun textUp(s: String, x: Float, y: Float, sizePx: Float, color: Int) {}
        override fun textWidth(s: String, sizePx: Float, bold: Boolean): Float = s.length * sizePx * (if (bold) 0.6f else 0.55f)
        override fun save() {}
        override fun clipRect(l: Float, t: Float, r: Float, b: Float) {}
        override fun restore() {}
    }

    private fun night(site: Site, date: LocalDate, vararg t: Target): List<NightResult> =
        t.map { ObservabilityCalculator(site, it, ObservabilityOptions()).night(date) }

    private val laSillaNight = night(lasilla, LocalDate.of(2026, 11, 24),
        Target("NGC 7531", 348.702083, -43.599944), Target("M42", 83.822083, -5.391111),
        Target("NGC 253", 11.887958, -25.288222), Target("M31", 10.684708, 41.268750),
        Target("NGC 104", 6.023625, -72.081278), Target("NGC 1399", 54.621167, -35.450667),
        Target("M104", 189.997633, -11.623054), Target("NGC 292", 13.186667, -72.828611))
    private val crowded = night(lapalma, LocalDate.of(2027, 2, 20),
        Target("M82", 148.968458, 69.679703), Target("M81", 148.888221, 69.065295), Target("M101", 210.802427, 54.348750))

    @Test
    fun boxNamesTheAirmassOnlyWhenNotOnTheCurve() {
        assertEquals(listOf("M82  alt 48.4°  az 7°  Moon 60°"),
            ChartText.cursorLines("M82", 48.4, 1.3312, 7.2, 60.4, false, false))
        assertEquals(listOf("M82  alt 48.4°  airmass 1.33  az 7°  Moon 60°"),
            ChartText.cursorLines("M82", 48.4, 1.3312, 7.2, 60.4, true, false))
        assertEquals(listOf("M82  alt 48.4°  airmass 1.33", "    az 7°  Moon 60°"),
            ChartText.cursorLines("M82", 48.4, 1.3312, 7.2, 60.4, true, true))
        // Below the horizon there is no airmass, on the curve or in the box.
        assertEquals(null, ChartText.airmassValue(-5.0, Double.POSITIVE_INFINITY))
        assertEquals(listOf("M101  alt -5.0°  az 41°  Moon 63°"),
            ChartText.cursorLines("M101", -5.0, Double.POSITIVE_INFINITY, 41.0, 63.0, true, false))
        assertEquals("1.33", ChartText.airmassValue(48.4, 1.3312))
    }

    @Test
    fun labelIsTheAirmassOfThePlottedCurve() {
        val r = laSillaNight[0]
        val model = r.options.airmassModel
        var checked = 0
        for (i in r.times.indices) {
            if (r.objAlt[i] <= 0.0) continue
            assertEquals(model.airmass(r.objAlt[i]), r.airmass[i], 1e-9)
            val v = ChartText.airmassValue(r.objAlt[i], r.airmass[i]) ?: continue
            assertEquals(r.airmass[i], v.toDouble(), 0.005 + 1e-9)
            checked++
        }
        assertTrue("checked $checked samples", checked > 50)
    }

    @Test
    fun everyAirmassShownExactlyOnceAndOnScreen() {
        val p = ChartPalette.DARK
        // (width px, height px, dp, sp): phone page, narrow phone with the largest font, landscape full screen
        val screens = listOf(
            floatArrayOf(1080f, 929f, 2.625f, 2.625f),
            floatArrayOf(882f, 787f, 2.625f, 3.41f),
            floatArrayOf(2200f, 940f, 2.625f, 2.625f),
        )
        var cases = 0
        var onCurve = 0
        var inBox = 0
        for (results in listOf(laSillaNight.take(3), laSillaNight, crowded)) {
            for (scr in screens) for (mode in YAxisMode.values()) for (h in -6..6) {
                val (w, ht, dp, sp) = scr.toList()
                val ren = NightChartRenderer(p, dp, sp)
                val cursor = results[0].midnight + h * 3_600_000L
                if (cursor !in results[0].plotStart..results[0].plotEnd) continue // no read-out outside the diagram
                val sink = RecordingSink()
                ren.draw(sink, w, ht, results, 0, mode, lasilla.zone, cursor, null)
                val i = results[0].indexAt(cursor)
                for ((k, rk) in results.withIndex()) {
                    val v = ChartText.airmassValue(rk.objAlt[i], rk.airmass[i])
                    val labels = sink.texts.count { it.bold && it.s == v && it.color == p.seriesColor(k) }
                    val boxed = sink.texts.count { !it.bold && it.s.startsWith("${rk.target.name}  ") && it.s.contains("airmass $v") }
                    val where = "${results.size} targets, ${w}x$ht sp=$sp $mode h=$h, ${rk.target.name} alt=${rk.objAlt[i]}"
                    if (v == null) {
                        assertEquals(where, 0, labels + boxed)
                    } else {
                        assertEquals(where, 1, labels + boxed)
                        // Points drawn on the diagram (on an airmass axis, only those above its bottom, 2.5), on
                        // screens with the normal font size.
                        if ((mode == YAxisMode.ALTITUDE || rk.airmass[i] <= 2.5) && sp == dp) {
                            onCurve += labels
                            inBox += boxed
                        }
                    }
                }
                // No "X 1.33" left in the box; the box and the labels stay on screen.
                for (d in sink.texts) {
                    val readout = d.s.contains("alt ") || d.s.contains("LST ") && d.s.contains(" · ") ||
                        d.bold && ChartText.airmassValue(1.0, d.s.toDoubleOrNull() ?: Double.NaN) != null
                    if (!readout) continue
                    assertTrue("'${d.s}' still uses X", !Regex("""\bX \d""").containsMatchIn(d.s))
                    val tw = sink.textWidth(d.s, d.size, d.bold)
                    assertTrue("'${d.s}' leaves the screen: x=${d.x} w=$tw of $w", d.x >= 0f && d.x + tw <= w + 0.5f)
                    assertTrue("'${d.s}' leaves the screen: y=${d.y} of $ht", d.y - d.size >= 0f && d.y <= ht + 0.5f)
                }
                cases++
            }
        }
        assertTrue("cases $cases", cases > 200)
        // With the normal font, nearly all the points drawn carry their label; the box takes those too close to
        // another point. (With the largest font on a narrow phone more go to the box: the diagram is small.)
        println("    $cases cursor positions; normal font, points drawn: $onCurve labelled on the curve, $inBox in the box")
        assertTrue("on curve $onCurve, in box $inBox", onCurve >= 9 * inBox)
    }
}
