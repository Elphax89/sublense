package app.sublens

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Normalizer
import java.util.Locale

/** A list to pick from (languages, scripts …) in a dialog, with a search field for long lists. */
object Picker {

    class Item(val label: String, val flag: String? = null, val sub: String? = null)

    fun show(activity: Activity, title: String, items: List<Item>, selected: Int, onPick: (Int) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var shown: List<Int> = items.indices.toList()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val search = if (items.size > 12) {
            EditText(activity).apply {
                hint = activity.getString(R.string.pick_search)
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setBackgroundResource(R.drawable.bg_field)
                setTextColor(activity.getColor(R.color.text_main))
                setHintTextColor(activity.getColor(R.color.text_dim))
                textSize = 15f
                setPadding(dp(14), 0, dp(14), 0)
                box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                    setMargins(dp(16), dp(4), dp(16), dp(8))
                })
            }
        } else {
            null
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = items[shown[position]]
            override fun getItemId(position: Int) = shown[position].toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_pick, parent, false)
                val index = shown[position]
                val item = items[index]
                v.findViewById<TextView>(R.id.pick_flag).apply {
                    text = item.flag ?: ""
                    visibility = if (item.flag == null) View.GONE else View.VISIBLE
                }
                v.findViewById<TextView>(R.id.pick_label).apply {
                    text = item.label
                    typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                v.findViewById<TextView>(R.id.pick_sub).apply {
                    text = item.sub ?: ""
                    visibility = if (item.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                v.findViewById<View>(R.id.pick_check).visibility = if (index == selected) View.VISIBLE else View.INVISIBLE
                return v
            }
        }

        val list = ListView(activity).apply {
            divider = null
            this.adapter = adapter
        }
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(items.size * dp(54), maxHeight)))

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            onPick(shown[position])
            dialog.dismiss()
        }
        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = plain(s?.toString() ?: "")
                shown = if (q.isEmpty()) items.indices.toList() else items.indices.filter { plain(items[it].label).contains(q) }
                adapter.notifyDataSetChanged()
            }
        })
        dialog.show()
        // Our card colours, with the usual margin around the dialog.
        activity.getDrawable(R.drawable.bg_card)?.let { dialog.window?.setBackgroundDrawable(InsetDrawable(it, dp(16), dp(24), dp(16), dp(24))) }
        if (selected in items.indices) list.setSelection((selected - 2).coerceAtLeast(0))
    }

    /** "Français" matches "francais". */
    private fun plain(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.getDefault()).trim()
}
