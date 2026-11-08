package cl.astro.observability.report

import cl.astro.observability.astro.NightResult

/**
 * One row of the night's visibility table (one per target). Rows are sorted by the time of minimum
 * airmass during dark time, the order in which the targets are best observed (as in iObserve).
 */
class VisibilityRow(
    /** Index of the target in the user's list (= its colour slot). */
    val index: Int,
    val result: NightResult,
    /** Time of minimum airmass during dark time, or null if never up in the dark. */
    val bestTime: Long?,
    val bestAirmass: Double,
    val bestAltitude: Double,
    val observableHours: Double,
    val observableMoonDownHours: Double,
    /** Start of the first and end of the last observable interval (dark, X ≤ limit). */
    val firstObservable: Long?,
    val lastObservable: Long?,
    /** Minimum Moon separation while both are up during dark time (NaN if they never are). */
    val minMoonSeparation: Double,
)

object VisibilityTable {
    fun rows(results: List<NightResult>): List<VisibilityRow> {
        val rows = results.mapIndexed { i, r ->
            val b = r.bestDarkIndex
            VisibilityRow(
                index = i,
                result = r,
                bestTime = b?.let { r.times[it] },
                bestAirmass = b?.let { r.airmass[it] } ?: Double.POSITIVE_INFINITY,
                bestAltitude = b?.let { r.objAlt[it] } ?: Double.NaN,
                observableHours = r.observableHours,
                observableMoonDownHours = r.observableMoonDownHours,
                firstObservable = r.observable.firstOrNull()?.start,
                lastObservable = r.observable.lastOrNull()?.end,
                minMoonSeparation = r.minMoonSeparationWithMoonUp,
            )
        }
        return rows.sortedWith(compareBy<VisibilityRow> { it.bestTime == null }.thenBy { it.bestTime ?: 0L })
    }
}
