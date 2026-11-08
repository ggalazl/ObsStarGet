// Desktop (JVM) preview of the app's diagrams: renders the same chart code as the app, through a
// Java2D implementation of DrawSink, to PNG files. Used to check the layout without a device.
//
//   kotlinc app/src/main/java/cl/astro/observability/{astro,data,chart}/*.kt validation/preview/PreviewMain.kt \
//       -include-runtime -d preview.jar && java -jar preview.jar <outdir>
package preview

import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.Site
import cl.astro.observability.chart.ChartPalette
import cl.astro.observability.chart.DrawSink
import cl.astro.observability.chart.NightChartRenderer
import cl.astro.observability.chart.TextAlign
import cl.astro.observability.chart.WindowChartRenderer
import cl.astro.observability.chart.SkyChartRenderer
import cl.astro.observability.chart.SeasonChartRenderer
import cl.astro.observability.astro.SeasonPlanner
import cl.astro.observability.astro.DarkLimit
import cl.astro.observability.report.Summaries
import cl.astro.observability.report.VisibilityTable
import cl.astro.observability.chart.YAxisMode
import cl.astro.observability.data.Catalog
import cl.astro.observability.data.SiteList
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.imageio.ImageIO

class Java2DSink(private val g: Graphics2D) : DrawSink {
    private val family = "DejaVu Sans"
    private val stack = ArrayList<java.awt.Shape?>()

    private fun color(c: Int) = Color(c, true)
    private fun font(size: Float, bold: Boolean) = Font(family, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    private fun stroke(w: Float, dash: FloatArray?) =
        if (dash == null) BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        else BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f)

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        g.color = color(color); g.fill(Rectangle2D.Float(l, t, r - l, b - t))
    }
    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float) {
        g.color = color(color); g.stroke = stroke(width, null); g.draw(Rectangle2D.Float(l, t, r - l, b - t))
    }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, dash: FloatArray?) {
        g.color = color(color); g.stroke = stroke(width, dash); g.draw(java.awt.geom.Line2D.Float(x0, y0, x1, y1))
    }
    override fun polyline(xs: FloatArray, ys: FloatArray, from: Int, to: Int, color: Int, width: Float, dash: FloatArray?) {
        if (to - from < 2) return
        val p = Path2D.Float()
        p.moveTo(xs[from], ys[from])
        for (i in from + 1 until to) p.lineTo(xs[i], ys[i])
        g.color = color(color); g.stroke = stroke(width, dash); g.draw(p)
    }
    override fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        val p = Path2D.Float()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until n) p.lineTo(xs[i], ys[i])
        p.closePath()
        g.color = color(color); g.fill(p)
    }
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        g.color = color(color); g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }
    override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        g.color = color(color); g.stroke = stroke(width, null); g.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }
    override fun text(s: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {
        g.font = font(sizePx, bold); g.color = color(color)
        val w = textWidth(s, sizePx, bold)
        val x0 = when (align) { TextAlign.LEFT -> x; TextAlign.CENTER -> x - w / 2; TextAlign.RIGHT -> x - w }
        g.drawString(s, x0, y)
    }
    override fun textUp(s: String, x: Float, y: Float, sizePx: Float, color: Int) {
        val old = g.transform
        g.translate(x.toDouble(), y.toDouble()); g.rotate(-Math.PI / 2)
        g.font = font(sizePx, false); g.color = color(color); g.drawString(s, 0f, 0f)
        g.transform = old
    }
    override fun textWidth(s: String, sizePx: Float, bold: Boolean): Float =
        g.getFontMetrics(font(sizePx, bold)).stringWidth(s).toFloat()
    override fun save() { stack.add(g.clip) }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { g.clip(Rectangle2D.Float(l, t, r - l, b - t)) }
    override fun restore() { g.clip = stack.removeAt(stack.size - 1) }
}

fun image(w: Int, h: Int, draw: (DrawSink) -> Unit): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    draw(Java2DSink(g))
    g.dispose()
    return img
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "previews" }).apply { mkdirs() }
    val assets = File(args.getOrElse(1) { "app/src/main/assets" })
    val cat = Catalog.parse(File(assets, "objects.tsv").bufferedReader())
    val sites = SiteList.parse(File(assets, "sites.tsv").bufferedReader())
    fun site(k: String): Site = sites.first { it.key == k }
    fun target(n: String) = cat.lookup(n)!!.toTarget("built-in")
    val dp = 2.625f
    val w = 1080
    val h = 860

    data class NightCase(val file: String, val site: String, val targets: List<String>, val date: LocalDate,
                         val mode: YAxisMode, val utc: Boolean, val cursorHours: Double?, val red: Boolean = false,
                         val fullDay: Boolean = false, val axisMax: Double = 2.5, val limit: Double = 2.0,
                         val zoneOverride: String? = null)
    val nightCases = listOf(
        NightCase("night_ngc7531_lasilla.png", "lasilla", listOf("NGC7531"), LocalDate.of(2026, 11, 7), YAxisMode.AIRMASS, false, 2.0),
        NightCase("night_multi_lasilla.png", "lasilla", listOf("NGC 7531", "M42", "NGC 253", "Andromeda Galaxy", "47 Tuc"),
            LocalDate.of(2026, 11, 24), YAxisMode.AIRMASS, false, null),
        NightCase("night_m31_lasilla_alt.png", "lasilla", listOf("Andromeda Galaxy"), LocalDate.of(2026, 11, 7), YAxisMode.ALTITUDE, false, null),
        NightCase("night_m82_lapalma_ut.png", "lapalma", listOf("M82", "M81", "M101"), LocalDate.of(2027, 2, 20), YAxisMode.AIRMASS, true, -1.0),
        NightCase("night_m42_picdumidi_red.png", "picdumidi", listOf("M42", "M1", "Pleiades"), LocalDate.of(2026, 12, 24), YAxisMode.AIRMASS, false, null, true),
        NightCase("night_radio_24h.png", "alma", listOf("Sgr A* region", "M83"), LocalDate.of(2027, 5, 1), YAxisMode.ALTITUDE, false, 3.0, false, true),
        NightCase("night_axis20_lasilla.png", "lasilla", listOf("NGC 7531", "M42", "NGC 253"), LocalDate.of(2026, 11, 24),
            YAxisMode.AIRMASS, false, 1.0, axisMax = 2.0, limit = 1.5),
        NightCase("night_axis30_lasilla.png", "lasilla", listOf("NGC 7531", "M42", "NGC 253"), LocalDate.of(2026, 11, 24),
            YAxisMode.AIRMASS, false, null, axisMax = 3.0),
        NightCase("night_axis15_limit25_lasilla.png", "lasilla", listOf("NGC 7531", "M42"), LocalDate.of(2026, 11, 24),
            YAxisMode.AIRMASS, false, null, axisMax = 1.5, limit = 2.5),
        NightCase("night_utcm2_lasilla.png", "lasilla", listOf("NGC 7531", "M42"), LocalDate.of(2026, 11, 24),
            YAxisMode.AIRMASS, false, 1.5, zoneOverride = "-02:00"),
        NightCase("night_paris_lasilla.png", "lasilla", listOf("NGC 7531"), LocalDate.of(2026, 11, 24),
            YAxisMode.AIRMASS, false, 1.5, zoneOverride = "Europe/Paris"),
    )
    for (c in nightCases) {
        val s = site(c.site)
        val opts = ObservabilityOptions(fullDay = c.fullDay, airmassLimit = c.limit)
        val rs = c.targets.map { name ->
            val t = cat.lookup(name)?.toTarget("built-in")
                ?: cl.astro.observability.astro.Target(name, 266.416833, -29.007806) // Sgr A* (not in OpenNGC)
            ObservabilityCalculator(s, t, opts).night(c.date)
        }
        val zone = c.zoneOverride?.let { ZoneId.of(it) } ?: if (c.utc) ZoneId.of("UTC") else s.zone
        val pal = if (c.red) ChartPalette.RED else ChartPalette.DARK
        val ren = NightChartRenderer(pal, dp, dp).apply { maxAirmass = c.axisMax }
        val cursor = c.cursorHours?.let { rs[0].midnight + (it * 3_600_000).toLong() }
        ImageIO.write(image(w, h) { ren.draw(it, w.toFloat(), h.toFloat(), rs, 0, c.mode, zone, cursor, null) }, "png", File(out, c.file))
        val sky = SkyChartRenderer(pal, dp, dp)
        ImageIO.write(image(w, w) { sky.draw(it, w.toFloat(), w.toFloat(), rs, 0, zone, cursor) }, "png",
            File(out, c.file.replace("night_", "sky_")))
        val r = rs[0]
        println("${c.file}: dark %.2f h, observable %.2f h (moon down %.2f h), transit alt %.1f, moon age %.2f d".format(
            r.darkHours, r.observableHours, r.observableMoonDownHours, r.events.transitAltDeg, r.moonAgeDays))
        for (row in VisibilityTable.rows(rs)) {
            println("   %-18s best %s X %.2f  obs %.1f h (moon down %.1f)  minSep %.0f".format(row.result.target.name,
                row.bestTime?.let { cl.astro.observability.chart.ChartText.hm(it, zone) } ?: "--", row.bestAirmass,
                row.observableHours, row.observableMoonDownHours, row.minMoonSeparation))
        }
    }

    data class WinCase(val file: String, val site: String, val target: String, val start: LocalDate, val nights: Int, val red: Boolean = false,
                       val axisMax: Double = 2.5)
    for (c in listOf(
        WinCase("window_ngc7531_lasilla_90.png", "lasilla", "NGC 7531", LocalDate.of(2026, 11, 7), 90),
        WinCase("window_m31_lapalma_365.png", "lapalma", "M31", LocalDate.of(2026, 11, 7), 365),
        WinCase("window_m82_lapalma_14.png", "lapalma", "Cigar Galaxy", LocalDate.of(2027, 2, 14), 14, true),
        WinCase("window_ngc7531_lasilla_90_axis20.png", "lasilla", "NGC 7531", LocalDate.of(2026, 11, 7), 90, axisMax = 2.0),
    )) {
        val s = site(c.site)
        val calc = ObservabilityCalculator(s, target(c.target), ObservabilityOptions())
        val t0 = System.nanoTime()
        val r = calc.window(c.start, c.nights, s.zone)!!
        val ms = (System.nanoTime() - t0) / 1e6
        val ren = WindowChartRenderer(if (c.red) ChartPalette.RED else ChartPalette.DARK, dp, dp).apply { maxAirmass = c.axisMax }
        ImageIO.write(image(w, h) { ren.draw(it, w.toFloat(), h.toFloat(), r, 3) }, "png", File(out, c.file))
        println("${c.file}: ${c.nights} nights in %.0f ms, y %.0f..%.0f h, step %d min".format(ms, r.yStartHours, r.yEndHours, r.stepMinutes))
    }

    // --- Best time of year
    data class SeasonCase(val file: String, val site: String, val target: String, val start: LocalDate,
                          val opts: ObservabilityOptions = ObservabilityOptions(), val red: Boolean = false, val cursor: Int? = null)
    for (c in listOf(
        SeasonCase("season_ngc7531_lasilla.png", "lasilla", "NGC 7531", LocalDate.of(2026, 11, 7), cursor = 300),
        SeasonCase("season_m31_lapalma.png", "lapalma", "M31", LocalDate.of(2026, 11, 7)),
        SeasonCase("season_lmc_lasilla.png", "lasilla", "LMC", LocalDate.of(2026, 11, 7)),
        SeasonCase("season_vega_ondrejov.png", "ondrejov", "Vega", LocalDate.of(2026, 11, 7)),
        SeasonCase("season_m42_picdumidi_red.png", "picdumidi", "M42", LocalDate.of(2026, 11, 7), red = true),
        SeasonCase("season_ngc7531_lapalma_x15.png", "lapalma", "NGC 7531", LocalDate.of(2026, 11, 7),
            ObservabilityOptions(airmassLimit = 1.5)),
    )) {
        val s = site(c.site)
        val t0 = System.nanoTime()
        val r = SeasonPlanner(s, target(c.target), c.opts).scan(c.start, 365)!!
        val ms = (System.nanoTime() - t0) / 1e6
        val ren = SeasonChartRenderer(if (c.red) ChartPalette.RED else ChartPalette.DARK, dp, dp)
        val sh = 760
        ImageIO.write(image(w, sh) { ren.draw(it, w.toFloat(), sh.toFloat(), r, c.cursor, s.zone) }, "png", File(out, c.file))
        println("${c.file}: %.0f ms\n  ".format(ms) + Summaries.season(r, s.zone).replace("\n", "\n  "))
    }

    // --- Full-screen layouts (portrait and landscape phone screens)
    run {
        val s = site("lasilla")
        val ts = listOf("NGC 7531", "M42", "NGC 253", "Andromeda Galaxy").map { target(it) }
        val rs = ts.map { ObservabilityCalculator(s, it, ObservabilityOptions()).night(LocalDate.of(2026, 11, 24)) }
        val win = ObservabilityCalculator(s, ts[0], ObservabilityOptions()).window(LocalDate.of(2026, 11, 7), 90, s.zone)!!
        val season = SeasonPlanner(s, ts[0], ObservabilityOptions()).scan(LocalDate.of(2026, 11, 7), 365)!!
        for ((tag, fw, fh) in listOf(Triple("portrait", 1080, 2000), Triple("landscape", 2200, 940))) {
            val cursor = rs[0].midnight + 3_600_000L
            ImageIO.write(image(fw, fh) { NightChartRenderer(ChartPalette.DARK, dp, dp).draw(it, fw.toFloat(), fh.toFloat(), rs, 0,
                YAxisMode.AIRMASS, s.zone, cursor, null) }, "png", File(out, "full_night_$tag.png"))
            ImageIO.write(image(fw, fh) { SkyChartRenderer(ChartPalette.DARK, dp, dp).draw(it, fw.toFloat(), fh.toFloat(), rs, 0, s.zone, cursor) },
                "png", File(out, "full_sky_$tag.png"))
            ImageIO.write(image(fw, fh) { WindowChartRenderer(ChartPalette.DARK, dp, dp).draw(it, fw.toFloat(), fh.toFloat(), win, 17) },
                "png", File(out, "full_window_$tag.png"))
            ImageIO.write(image(fw, fh) { SeasonChartRenderer(ChartPalette.DARK, dp, dp).draw(it, fw.toFloat(), fh.toFloat(), season, 120, s.zone) },
                "png", File(out, "full_season_$tag.png"))
        }
    }
}
