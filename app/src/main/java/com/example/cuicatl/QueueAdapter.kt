package com.example.cuicatl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Lo que viene en la cola (sin la canción que suena). Tocar una fila salta a esa canción,
 * la ✕ la quita y, al arrastrar, se reordena con [mover].
 */
class QueueAdapter(
    private val onClick: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = mutableListOf<Song>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvNum: TextView = view.findViewById(R.id.tvQueueNum)
        val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvQueueArtist)
        val btnRemove: View = view.findViewById(R.id.btnQueueRemove)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        val ctx = holder.itemView.context

        holder.tvNum.text = ctx.getString(R.string.queue_num, position + 1)
        holder.tvTitle.text = SongStore.displayTitle(ctx, song)
        holder.tvArtist.text = SongStore.displayArtist(ctx, song)

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        holder.btnRemove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    /** Pone la lista nueva calculando qué cambió (sin notifyDataSetChanged). */
    fun submit(nuevos: List<Song>) {
        val viejos = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = viejos.size
            override fun getNewListSize() = nuevos.size
            override fun areItemsTheSame(o: Int, n: Int) = viejos[o].path == nuevos[n].path

            // El número de cada fila depende de su posición, así que siempre se repinta
            override fun areContentsTheSame(o: Int, n: Int) = false
        })
        items.clear()
        items.addAll(nuevos)
        diff.dispatchUpdatesTo(this)
    }

    /** Mueve una fila mientras se arrastra. */
    fun mover(desde: Int, hasta: Int) {
        items.add(hasta, items.removeAt(desde))
        notifyItemMoved(desde, hasta)
    }
}
