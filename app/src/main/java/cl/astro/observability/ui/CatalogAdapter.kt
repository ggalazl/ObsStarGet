package cl.astro.observability.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import cl.astro.observability.data.Catalog

/** Name completion from the offline catalogue (filtering runs on the widget's worker thread). */
class CatalogAdapter(
    private val context: Context,
    private val catalogProvider: () -> Catalog?,
    private val textColor: Int,
) : BaseAdapter(), Filterable {

    private var items: List<Pair<String, Catalog.Entry>> = emptyList()

    fun entryAt(position: Int): Catalog.Entry? = items.getOrNull(position)?.second

    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position].second.name
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textColor)
            maxLines = 2
        }
        val (label, e) = items[position]
        tv.text = if (e.type.isNotEmpty()) "$label  —  ${e.type}" else label
        return tv
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val res = FilterResults()
            val cat = catalogProvider()
            val list = if (cat == null || constraint == null || constraint.length < 2) emptyList()
            else cat.suggest(constraint.toString(), 40)
            res.values = list
            res.count = list.size
            return res
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items = (results?.values as? List<Pair<String, Catalog.Entry>>) ?: emptyList()
            if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence = resultValue?.toString() ?: ""
    }
}
