package de.ugur.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class Row {
    data class GroupRow(val key: String, val title: String, val count: Int) : Row()
    data class Item(val channel: Channel) : Row()
    data class Info(val text: String) : Row()
    data class Back(val title: String) : Row()
}

class RowAdapter(
    private val store: Store,
    private val onGroup: (Int, Row.GroupRow) -> Unit,
    private val onItem: (Channel) -> Unit,
    private val onItemLong: (Channel) -> Unit,
    private val onBack: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()
        private set

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.GroupRow -> TYPE_GROUP
        is Row.Item -> TYPE_ITEM
        is Row.Info -> TYPE_INFO
        is Row.Back -> TYPE_BACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ITEM -> ItemHolder(inflater.inflate(R.layout.row_item, parent, false))
            TYPE_INFO -> InfoHolder(inflater.inflate(R.layout.row_info, parent, false))
            else -> GroupHolder(inflater.inflate(R.layout.row_group, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.GroupRow -> (holder as GroupHolder).bind(row.title, row.count.toString(), "›") {
                onGroup(holder.bindingAdapterPosition, row)
            }
            is Row.Back -> (holder as GroupHolder).bind("‹  " + row.title, "", "") { onBack() }
            is Row.Item -> (holder as ItemHolder).bind(row.channel)
            is Row.Info -> (holder as InfoHolder).bind(row.text)
        }
    }

    inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.logo)
        private val name: TextView = view.findViewById(R.id.name)
        private val star: TextView = view.findViewById(R.id.star)

        fun bind(ch: Channel) {
            name.text = ch.name
            star.visibility = if (store.isFavorite(ch)) View.VISIBLE else View.GONE
            Logos.load(logo, ch.logo)
            itemView.setOnClickListener { onItem(ch) }
            itemView.setOnLongClickListener {
                onItemLong(ch)
                true
            }
        }
    }

    class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val count: TextView = view.findViewById(R.id.count)
        private val arrow: TextView = view.findViewById(R.id.arrow)

        fun bind(t: String, c: String, a: String, click: () -> Unit) {
            title.text = t
            count.text = c
            arrow.text = a
            itemView.setOnClickListener { click() }
        }
    }

    class InfoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view as TextView

        fun bind(t: String) {
            text.text = t
        }
    }

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_INFO = 2
        private const val TYPE_BACK = 3
    }
}
